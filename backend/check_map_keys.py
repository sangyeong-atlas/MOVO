"""Read-only provider smoke check. Prints statuses, never keys or account data."""
import json
from routing import NaverDirections, RouteError
from server_v2 import KAKAO_KEY, kakao_places


def main():
    status = {'kakao': 'not_configured', 'naver': 'not_configured'}
    if KAKAO_KEY:
        places = kakao_places(37.5447, 127.0558, 'CE7', size=1)
        status['kakao'] = 'ok' if places is not None else 'request_failed'
    provider = NaverDirections()
    if provider.configured:
        try:
            result = provider.fetch(((37.5447, 127.0558), (37.5445, 127.0374)))
            status['naver'] = 'ok' if len(result['polyline']) > 2 else 'check_geometry'
        except RouteError as error:
            status['naver'] = str(error)
    print(json.dumps(status, ensure_ascii=False))
    return 0 if all(value == 'ok' for value in status.values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
