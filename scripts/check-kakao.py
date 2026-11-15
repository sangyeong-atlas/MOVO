"""Read-only key verification; never log keys or SDK response bodies."""
import sys
from pathlib import Path
import urllib.request, urllib.parse, urllib.error, json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
import server_v2 as v
places=v.kakao_places(0,0,query='성수연방')
print(json.dumps({'searchConnected':places is not None,'results':len(places or []),'firstPlace':places[0]['name'] if places else None},ensure_ascii=False))
key=v.os.getenv('KAKAO_JAVASCRIPT_KEY') or v.os.getenv('KAKAO_JAVASCRIPT_API_KEY','')
try:
 req=urllib.request.Request('https://dapi.kakao.com/v2/maps/sdk.js?'+urllib.parse.urlencode({'appkey':key,'autoload':'false'}),headers={'Referer':(sys.argv[1] if len(sys.argv)>1 else 'http://localhost:5173')+'/'})
 with urllib.request.urlopen(req,timeout=15) as r:print('SDK HTTP',r.status,'bytes',len(r.read()))
except urllib.error.HTTPError as e:print('SDK HTTP',e.code,e.read().decode().replace(key,'[REDACTED]')[:500])
except Exception as e:print('SDK error',type(e).__name__)
