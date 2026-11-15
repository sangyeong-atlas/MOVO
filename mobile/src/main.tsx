import React from 'react';
import {createRoot} from 'react-dom/client';
import App from './App';
import {TwelveScreens} from './TwelveScreens';
import {VehicleLab} from './VehicleLab';
import {MultiDemo} from './MultiDemo';
import L from 'leaflet';
import leafletCss from 'leaflet/dist/leaflet.css?inline';
import 'leaflet/dist/leaflet.css';
// The B document remains isolated; expose only the map renderer and its stylesheet.
Object.assign(window,{movoMapRenderer:{L,css:leafletCss}});
import './styles.css';
import './movo-b.css';
const params = new URLSearchParams(location.search);
const root = params.has('vehicle') ? <VehicleLab/> : params.has('multi') ? <MultiDemo/> : (params.has('functional')||params.has('invite')) ? <App/> : <TwelveScreens/>;
createRoot(document.getElementById('root')!).render(<React.StrictMode>{root}</React.StrictMode>);
if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
