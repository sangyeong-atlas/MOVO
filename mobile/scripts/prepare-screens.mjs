import {readFileSync,writeFileSync} from 'node:fs';
const source=new URL('../public/MOVO-mobile-B-best-uiux.html',import.meta.url);
const target=new URL('../public/screens.html',import.meta.url);
writeFileSync(target,readFileSync(source,'utf8').replace('</body>','<script type="module" src="/screens.js"></script></body>'));
