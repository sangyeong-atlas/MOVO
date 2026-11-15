"""Shared, bounded, in-memory Naver routing. Network work never locks Journey/SSE."""
import copy
import hashlib
import json
import math
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def load_local_keys():
    # python.org macOS builds may lack their own CA link. Use the OS trust bundle, never disable TLS verification.
    if sys.platform == 'darwin' and not ssl.get_default_verify_paths().cafile and Path('/etc/ssl/cert.pem').is_file():
        os.environ.setdefault('SSL_CERT_FILE', '/etc/ssl/cert.pem')
    allowed = {'KAKAO_REST_API_KEY', 'NAVER_MAPS_CLIENT_ID', 'NAVER_MAPS_CLIENT_SECRET',
               'KAKAO_JAVASCRIPT_KEY', 'KAKAO_JAVASCRIPT_API_KEY'}
    # Explicit environment wins, then the new local file, then the existing B-app file.
    paths = [Path(__file__).with_name('.env.local'), Path(os.getenv('MOVO_ENV_FILE', Path(__file__).with_name('.env')))]
    for path in paths:
        if path.is_file():
            for line in path.read_text().splitlines():
                name, sep, value = line.strip().partition('=')
                if sep and name.strip() in allowed:
                    os.environ.setdefault(name.strip(), value.strip().strip('"').strip("'"))



class RouteError(Exception):
    pass


def number(value, maximum):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and abs(value) <= maximum


def coordinates(points):
    result = []
    for p in points:
        if not number(p.get('lat'), 90) or not number(p.get('lng'), 180):
            raise RouteError('invalid_coordinates')
        point = (p['lat'], p['lng'])
        if not result or point != result[-1]:
            result.append(point)
    if len(result) < 2:
        raise RouteError('same_location')
    if len(result) > 17:
        raise RouteError('too_many_waypoints')
    return tuple(result)


class NaverDirections:
    def __init__(self, client_id=None, client_secret=None, opener=None):
        self.client_id = os.getenv('NAVER_MAPS_CLIENT_ID', '') if client_id is None else client_id
        self.client_secret = os.getenv('NAVER_MAPS_CLIENT_SECRET', '') if client_secret is None else client_secret
        self.opener = opener or urllib.request.urlopen

    @property
    def configured(self):
        return bool(self.client_id and self.client_secret)

    def fetch(self, points):
        if not self.configured:
            raise RouteError('not_configured')
        def xy(p):
            return f'{p[1]},{p[0]}'
        params = {'start': xy(points[0]), 'goal': xy(points[-1]), 'option': 'traoptimal'}
        if len(points) > 2:
            params['waypoints'] = '|'.join(map(xy, points[1:-1]))
        service = 'map-direction' if len(points) <= 7 else 'map-direction-15'
        request = urllib.request.Request(
            f'https://maps.apigw.ntruss.com/{service}/v1/driving?' + urllib.parse.urlencode(params),
            headers={'x-ncp-apigw-api-key-id': self.client_id, 'x-ncp-apigw-api-key': self.client_secret})
        try:
            with self.opener(request, timeout=5) as response:
                data = json.load(response)
            if data.get('code') != 0:
                raise RouteError('no_route')
            route = data['route']['traoptimal'][0]
            summary, path = route['summary'], route['path']
            if len(path) < 2 or any(len(p) != 2 or not number(p[0], 180) or not number(p[1], 90) for p in path):
                raise RouteError('invalid_response')
            distance, duration = summary['distance'], summary['duration']
            if not number(distance, 100000000) or distance < 0 or not number(duration, 10000000000) or duration < 0:
                raise RouteError('invalid_response')
            # Guide durations are per section; type 87 denotes a waypoint.
            cumulative, waypoint_seconds = 0, []
            for guide in route.get('guide', []):
                delta = guide.get('duration')
                if not number(delta, 10000000000) or delta < 0:
                    waypoint_seconds = []
                    break
                cumulative += delta
                if guide.get('type') == 87:
                    waypoint_seconds.append(round(cumulative / 1000))
            if len(waypoint_seconds) != len(points) - 2:
                waypoint_seconds = []
            return {'source': 'naver', 'status': 'ready', 'polyline': [[p[1], p[0]] for p in path],
                    'remainingMeters': round(distance), 'drivingSeconds': round(duration / 1000),
                    'waypointSeconds': waypoint_seconds, 'queriedAt': time.time(),
                    'routeId': hashlib.sha256(json.dumps([points, path, duration]).encode()).hexdigest()[:24]}
        except urllib.error.HTTPError as error:
            raise RouteError('authentication' if error.code in (401, 403) else 'quota' if error.code == 429 else 'provider_error') from None
        except RouteError:
            raise
        except Exception:
            raise RouteError('provider_unavailable') from None


class RoadRoutes:
    def __init__(self, provider=None, ttl=60, clock=time.monotonic):
        self.provider = provider or NaverDirections()
        self.ttl, self.clock = ttl, clock
        self.lock = threading.RLock()
        self.cache, self.scopes = {}, {}
        self.pool = ThreadPoolExecutor(max_workers=3, thread_name_prefix='road-routes')
        self.slots = threading.BoundedSemaphore(24)

    def close(self):
        self.pool.shutdown(wait=True)

    @staticmethod
    def unavailable(reason):
        return {'source': 'unavailable', 'status': 'unavailable', 'reason': reason,
                'polyline': [], 'remainingMeters': None, 'drivingSeconds': None}

    def request(self, stops, scope=None):
        if not self.provider.configured:
            return self.unavailable('not_configured')
        try:
            points = coordinates(stops)
        except RouteError as error:
            return self.unavailable(str(error))
        with self.lock:
            now = self.clock()
            if scope is not None:
                old = self.scopes.get(scope)
                if old and old[1][1:] == points[1:] and now - old[0] < self.ttl:
                    a, b = old[1][0], points[0]
                    meters = math.hypot((a[0]-b[0])*111000, (a[1]-b[1])*111000*math.cos(math.radians(b[0])))
                    if meters < 100:
                        points = old[1]
                if not old or old[1] != points or now - old[0] >= self.ttl:
                    self.scopes[scope] = (now, points)
                if len(self.scopes) > 256:
                    self.scopes.pop(next(iter(self.scopes)))
            old = self.cache.get(points)
            if old and now < old[0]:
                return copy.deepcopy(old[1])
            if not self.slots.acquire(blocking=False):
                return self.unavailable('busy')
            result = {'source': 'naver', 'status': 'pending', 'polyline': [], 'remainingMeters': None, 'drivingSeconds': None}
            self.cache[points] = (now + 30, result)
            if len(self.cache) > 256:
                for key, (_, value) in list(self.cache.items()):
                    if value['status'] != 'pending' and key != points:
                        del self.cache[key]
                        if len(self.cache) <= 256:
                            break
            self.pool.submit(self._fetch, points)
            return copy.deepcopy(result)

    def _fetch(self, points):
        try:
            try:
                result = self.provider.fetch(points)
                result['_points'] = points
            except RouteError as error:
                result = self.unavailable(str(error))
            except Exception:
                result = self.unavailable('provider_unavailable')
            with self.lock:
                self.cache[points] = (self.clock() + (self.ttl if result['status'] == 'ready' else 30), result)
        finally:
            self.slots.release()

    def detours(self, baseline, places):
        """Match stop approval: append the candidate immediately before the destination."""
        results = []
        for place in places[:5]:
            result = dict(place, detourSeconds=None, detourMeters=None, detourStatus=baseline['status'])
            if baseline['status'] == 'ready':
                points = [dict(lat=p[0], lng=p[1]) for p in baseline['_points']]
                index = baseline.get('_insertionIndex', len(points)-1)
                candidate = self.request(points[:index] + [place] + points[index:])
                result['detourStatus'] = candidate['status']
                if candidate['status'] == 'ready':
                    result.update(detourSeconds=max(0, candidate['drivingSeconds']-baseline['drivingSeconds']),
                                  detourMeters=max(0, candidate['remainingMeters']-baseline['remainingMeters']),
                                  detourSource='naver', detourRouteId=baseline['routeId'])
            results.append(result)
        return results


load_local_keys()
ROADS = RoadRoutes()
