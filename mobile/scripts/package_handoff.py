from pathlib import Path
import zipfile,hashlib,json
ROOT=Path(__file__).resolve().parents[1];out=ROOT/'releases';out.mkdir(exist_ok=True);target=out/'hopon-vehicle-handoff-0.2.0.zip'
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((ROOT/'handoff').rglob('*')):
  if p.is_file() and '__pycache__' not in p.parts:z.write(p,p.relative_to(ROOT/'handoff'))
 for name in ['server.py','legacy.py']:z.write(ROOT/'backend'/name,'backend/'+name)
 z.writestr('VERSION','0.2.0\n')
 z.writestr('MANIFEST.json',json.dumps({'version':'0.2.0','contents':'contracts, client, simulator, clean API source','excluded':['DB','sessions','tokens','logs','real-user locations','secret keys']},indent=2))
print(target);print('sha256',hashlib.sha256(target.read_bytes()).hexdigest())
