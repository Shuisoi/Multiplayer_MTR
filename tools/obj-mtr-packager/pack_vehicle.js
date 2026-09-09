// MMTR OBJ vehicle packager v1 — reads vehicle.json, normalizes Blender OBJ -> MTR resource pack zip
const fs=require('fs');
const path=require('path');
const {spawnSync}=require('child_process');
const {writeZip}=require('./zip.js');
const {resolveRoot}=require('./paths.js');
const params=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
// 配置里的路径可以写 ${MC_ROOT} 占位符（见 paths.js），这样配置文件能入库而不带机器相关绝对路径。
for(const key of ['sourceObj','textureDir','outputDir','extraAnchors','stagingDir']){
  if(params[key]) params[key]=resolveRoot(params[key]);
}
const id=params.id;
const base=path.dirname(params.sourceObj);
const stage=params.stagingDir||path.join(base,'.pack_stage_'+id);
fs.rmSync(stage,{recursive:true,force:true});
const aMtr=path.join(stage,'assets','mtr');
const sub=path.join(aMtr,id);
fs.mkdirSync(sub,{recursive:true});
// MTR lowercases every resource path before looking it up (CustomResourceTools.formatIdentifierString),
// so the OBJ/MTL/PNG file names in the pack MUST be lowercase or the model silently loads as "".
const srcBase=path.basename(params.sourceObj).toLowerCase();
const raw=fs.readFileSync(params.sourceObj,'utf8');
// parse lines preserving structure; transform v/vn lines
const deg=params.rotationDegY||0, rad=deg*Math.PI/180, cs=Math.cos(rad), sn=Math.sin(rad);
const out=[]; const vpos=[];
for(const line of raw.split('\n')){
  const t=line.trim();
  if(t.startsWith('v ')){ const p=t.split(/\s+/).slice(1,4).map(Number); vpos.push([cs*p[0]+sn*p[2], p[1], -sn*p[0]+cs*p[2]]); }
}
let cx=0,cz=0;
if(params.recenter!==false && vpos.length){
  let mnX=1e9,mxX=-1e9,mnZ=1e9,mxZ=-1e9;
  for(const v of vpos){ if(v[0]<mnX)mnX=v[0]; if(v[0]>mxX)mxX=v[0]; if(v[2]<mnZ)mnZ=v[2]; if(v[2]>mxZ)mxZ=v[2]; }
  cx=(mnX+mxX)/2; cz=(mnZ+mxZ)/2;
}
const groupMap=params.groupMap||{};
// Optional source-name -> canonical-name map, applied before role matching. Blender exports often
// carry names that clash with the pack's conventions (e.g. "mmtr_door_l_1" would otherwise match the
// mmtr_ anchor prefix, and a reversed cab car needs its anchors moved to the other cab). Renaming
// here keeps groupMap and every downstream name (parts, doorways, anchors) consistent.
const groupRename=params.groupRename||{};
function renameGroup(name){ return groupRename[name]||name; }
function roleOf(name){ let best=null,bestLen=-1; for(const role of Object.keys(groupMap)) for(const pat of groupMap[role]) if(name.indexOf(pat)>=0 && pat.length>bestLen){ best=role; bestLen=pat.length; } return best; }
const used={};
function canonical(name){ const r=roleOf(name); if(r){used[r]=1; return r;} return null; }
// Door groups keep their own names so every door is a separate MTR part (its own slide + DOORWAY):
//   door_l_1 / door_l_2 ... = 左侧第1/2扇客车门;  door_r_1 / door_r_2 ... = 右侧
//   mmtr_cabdoor_<cab>_<n> = 驾驶室 <cab>(1=A端,2=B端) 的第 <n> 扇司机门 (anchor role, not rendered)
const doorGroups=[];
const anchorFaces=[]; let currentRole=null; let currentAnchor=null;
// mmtr_cabdoor_* is special: it is BOTH a visible part (the driver's door you can see and aim at)
// and an anchor. Other mmtr_* faces (hud/seat/ack) are pure data and get stripped from the geometry.
const cabDoorParts=[];
let vi=0;
// Data-only anchor geometry is dropped entirely: faces stripped AND vertices removed, because an OBJ
// has no per-group vertex scoping - leaving them in would attach them to whichever group was written
// last and inflate that part's bounding box (MTR builds door/part boxes from the group bounds).
// Dropping vertices shifts the global index space, so faces are renumbered through vRemap.
const vRemap=[]; let vOut=0;
const remapFace=line=>'f '+line.split(/\s+/).slice(1).map(ref=>{ const p=ref.split('/'); p[0]=String(vRemap[+p[0]]||0); return p.join('/'); }).join(' ');
for(const line of raw.split('\n')){
  const t=line.trim();
  if(t.startsWith('v ')){
    const srcIndex=++vi; const v=vpos[srcIndex-1];
    if(currentRole==='anchor'){ vRemap[srcIndex]=0; continue; }
    vRemap[srcIndex]=++vOut;
    out.push('v '+((v[0]-cx).toFixed(6))+' '+v[1].toFixed(6)+' '+((v[2]-cz).toFixed(6)));
  }
  else if(t.startsWith('vn ')){ const p=t.split(/\s+/).slice(1,4).map(Number); out.push('vn '+(cs*p[0]+sn*p[2]).toFixed(6)+' '+p[1].toFixed(6)+' '+(-sn*p[0]+cs*p[2]).toFixed(6)); }
  else if(t.startsWith('o ')||t.startsWith('g ')){
    // Blender duplicates object names with a ".001" suffix - strip it so roles/anchors match cleanly.
    const raw2=renameGroup(t.slice(2).trim().replace(/\.\d+$/,'')); const cn=canonical(raw2);
    if(cn==='anchor'){
      const cabDoorMatch=/^mmtr_cabdoor_/i.test(raw2);
      currentAnchor={name:raw2,faces:[]};
      anchorFaces.push(currentAnchor);
      if(cabDoorMatch){
        // Keep the geometry: the cab door must be visible (and aimable) in game.
        const partName=raw2.replace(/^mmtr_/i,'');
        if(!cabDoorParts.includes(partName)) cabDoorParts.push(partName);
        currentRole=partName;
        out.push('g '+partName);
      } else {
        currentRole='anchor';
      }
    }
    else if(cn==='door_l'||cn==='door_r'){ if(!doorGroups.includes(raw2)) doorGroups.push(raw2); currentRole=raw2; currentAnchor=null; out.push('g '+raw2); }
    else if(cn){ currentRole=cn; currentAnchor=null; out.push('g '+cn); }
    else { currentRole=null; currentAnchor=null; }
  }
  else if(t.startsWith('f ')){
    // Anchor faces are always collected (even when the group is also rendered, like the cab door);
    // data-only anchor geometry is not written out at all.
    if(currentAnchor){ currentAnchor.faces.push(t.split(/\s+/).slice(1).map(x=>+x.split('/')[0])); }
    if(currentRole!=='anchor') out.push(remapFace(t));
  }
  else if(t.startsWith('vt ')||t.startsWith('s ')||t.startsWith('usemtl ')) out.push(line);
  else if(t.startsWith('mtllib ')){ out.push('mtllib '+srcBase.replace(/\.obj$/i,'')+'.mtl'); }
  else if(!t.startsWith('#')) out.push(line);
}
const objMain=out.join('\n');

// ---- MMTR cab HUD anchors: centre + orthonormal frame + size of each mmtr_hud* quad ----------
function buildAnchors(){
  const anchors=[];
  const rc=i=>{ const v=vpos[i-1]; return [v[0]-cx, v[1], v[2]-cz]; };
  const sub=(a,c)=>[a[0]-c[0], a[1]-c[1], a[2]-c[2]];
  const cr=(a,b)=>[a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
  const nz=v=>{ const l=Math.hypot(v[0],v[1],v[2])||1; return [v[0]/l,v[1]/l,v[2]/l]; };
  const dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
  for(const g of anchorFaces){
    const ids=[];
    for(const f of g.faces) for(const i of f) if(!ids.includes(i)) ids.push(i);
    if(ids.length<3) continue;
    const p=ids.map(rc);
    const c=[0,0,0]; for(const v of p){ c[0]+=v[0]; c[1]+=v[1]; c[2]+=v[2]; } c[0]/=p.length; c[1]/=p.length; c[2]/=p.length;
    // Reference face = the largest one (an anchor may be a box, e.g. a door leaf; the first face
    // could be a thin side and give a tilted frame). Area via Newell's method.
    let f0=g.faces[0], bestArea=-1;
    for(const f of g.faces){
      if(f.length<3) continue;
      let nx=0,ny=0,nz=0;
      for(let i=0;i<f.length;i++){ const a=rc(f[i]), b=rc(f[(i+1)%f.length]); nx+=(a[1]-b[1])*(a[2]+b[2]); ny+=(a[2]-b[2])*(a[0]+b[0]); nz+=(a[0]-b[0])*(a[1]+b[1]); }
      const area=Math.hypot(nx,ny,nz)/2;
      if(area>bestArea){ bestArea=area; f0=f; }
    }
    let n=nz(cr(sub(rc(f0[1]),c), sub(rc(f0[2]),c)));
    // Blender face winding decides the normal. Flip it globally with flipAnchorNormal, or per anchor
    // with flipAnchorNormalByGroup: ["mmtr_hud", ...] (matches the object name or the anchor name).
    const rawNameForFlip=(g.name||'').replace(/\.\d+$/,'');
    const anchorNameForFlip=rawNameForFlip.replace(/^mmtr_/,'');
    const flipList=params.flipAnchorNormalByGroup||[];
    const flipThis=params.flipAnchorNormal||flipList.includes(rawNameForFlip)||flipList.includes(anchorNameForFlip);
    if(flipThis) n=n.map(x=>-x);
    // up = the quad edge most aligned with world +Y, orthogonalised against the normal;
    // right = up x normal (right-handed frame: right = HUD 右, up = HUD 上, normal = HUD 朝向)
    const e1=sub(rc(f0[1]), rc(f0[0]));
    const e2=sub(rc(f0[2]), rc(f0[1]));
    let upRaw=Math.abs(e1[1])>=Math.abs(e2[1]) ? e1 : e2;
    let up=nz([upRaw[0]-n[0]*dot(upRaw,n), upRaw[1]-n[1]*dot(upRaw,n), upRaw[2]-n[2]*dot(upRaw,n)]);
    const right=nz(cr(up,n));
    let wMax=-1e9,wMin=1e9,hMax=-1e9,hMin=1e9;
    for(const v of p){ const d=sub(v,c); const dr=dot(d,right), dh=dot(d,up); if(dr>wMax)wMax=dr; if(dr<wMin)wMin=dr; if(dh>hMax)hMax=dh; if(dh<hMin)hMin=dh; }
    const rawName=(g.name||'anchor').replace(/^mmtr_/,'')||'anchor';
    // Structured naming (user convention): <kind>[_<cab>][_<index>]
    //   cabdoor_1_2  = 驾驶室1 的第2扇门   hud_2 = 驾驶室2 的仪表   seat_1 = 驾驶室1 座位
    //   cab 1 = A 端 (CAB_A), cab 2 = B 端 (CAB_B); no cab = single-cab model (defaults to 1)
    const m=/^(hud|seat|cabdoor|ack)(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    const kind=m?m[1]:rawName;
    const cab=m&&m[2]?+m[2]:null;
    const door=m&&m[3]?+m[3]:null;
    anchors.push({name:rawName,kind:kind,cab:cab,door:door,car:params.carIndex||0,
      x:+c[0].toFixed(5), y:+c[1].toFixed(5), z:+c[2].toFixed(5),
      normal:n.map(x=>+x.toFixed(6)), up:up.map(x=>+x.toFixed(6)), right:right.map(x=>+x.toFixed(6)),
      widthM:+(wMax-wMin).toFixed(4), heightM:+(hMax-hMin).toFixed(4)});
  }
  return anchors;
}
const anchors=buildAnchors();
// Blender empties are NOT exported to OBJ, so point-like anchors (seat/ack) can come from a sidecar
// file instead: params.extraAnchors, or <textureDir>/mmtr_anchors_extra.json. Coordinates are in the
// SOURCE file space (same as the OBJ before rotation) and are rotated/recentred like vertices here.
//   {"anchors":[{"name":"mmtr_seat_1","x":3.0,"y":1.2,"z":0.0,"normal":[1,0,0]}, ...]}
const extraAnchorsPath=params.extraAnchors||path.join(params.textureDir||base,'mmtr_anchors_extra.json');
if(fs.existsSync(extraAnchorsPath)){
  const extra=JSON.parse(fs.readFileSync(extraAnchorsPath,'utf8'));
  for(const a of (extra.anchors||[])){
    const px=cs*a.x+sn*(a.z||0)-cx, pz=-sn*a.x+cs*(a.z||0)-cz;
    const nIn=a.normal||[0,0,1];
    const nx=cs*nIn[0]+sn*nIn[2], nz2=-sn*nIn[0]+cs*nIn[2];
    const nLen=Math.hypot(nx,nIn[1],nz2)||1;
    const n=[nx/nLen,nIn[1]/nLen,nz2/nLen];
    // up = world +Y orthogonalised against the normal, right = up x normal (right-handed frame)
    const upRaw=[0,1,0];
    const dn=upRaw[0]*n[0]+upRaw[1]*n[1]+upRaw[2]*n[2];
    let up=[upRaw[0]-n[0]*dn, upRaw[1]-n[1]*dn, upRaw[2]-n[2]*dn];
    const uLen=Math.hypot(up[0],up[1],up[2]);
    up=uLen<1e-6?[0,1,0]:[up[0]/uLen,up[1]/uLen,up[2]/uLen];
    const right=[up[1]*n[2]-up[2]*n[1], up[2]*n[0]-up[0]*n[2], up[0]*n[1]-up[1]*n[0]];
    const rawName=(a.name||'anchor').replace(/^mmtr_/,'')||'anchor';
    const m=/^(hud|seat|cabdoor|ack)(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    anchors.push({name:rawName,kind:a.kind||(m?m[1]:rawName),cab:a.cab!==undefined?a.cab:(m&&m[2]?+m[2]:null),door:a.door!==undefined?a.door:(m&&m[3]?+m[3]:null),car:params.carIndex||0,
      x:+px.toFixed(5), y:+(a.y||0).toFixed(5), z:+pz.toFixed(5),
      normal:n.map(x=>+x.toFixed(6)), up:up.map(x=>+x.toFixed(6)), right:right.map(x=>+x.toFixed(6)),
      widthM:+(a.widthM||0).toFixed(4), heightM:+(a.heightM||0).toFixed(4)});
  }
  console.log('extra anchors from '+extraAnchorsPath+': '+(extra.anchors||[]).length);
}
// One DOORWAY slab per door group (a car with several door pairs must not get one huge slab).
// A locomotive has no passenger doors - only cab doors - so the config can name extra parts that must
// still get a doorway (params.extraDoorways), otherwise the crew could never board it.
const extraDoorwayNames=new Set((params.extraDoorways||[]).map(n=>String(n)));
function isDoorwaySource(name){ return name.startsWith('door_l')||name.startsWith('door_r')||extraDoorwayNames.has(name); }
function doorBBoxes(){
  const res={}; const vv=[]; let cur=null;
  for(const l of out){ const t=l.trim();
    if(t.startsWith('v ')) vv.push(t.split(/\s+/).slice(1,4).map(Number));
    else if(t.startsWith('g ')) cur=t.slice(2).trim();
    else if(t.startsWith('f ')&&cur&&isDoorwaySource(cur)){
      const b=res[cur]||(res[cur]={mn:[1e9,1e9,1e9],mx:[-1e9,-1e9,-1e9]});
      for(const i of t.split(/\s+/).slice(1).map(x=>+x.split('/')[0])){ const v=vv[i-1]; if(v) for(let c=0;c<3;c++){ if(v[c]<b.mn[c])b.mn[c]=v[c]; if(v[c]>b.mx[c])b.mx[c]=v[c]; } }
    }
  }
  return res;
}
const dbs=doorBBoxes();
const doorwayGroups=[];
let hasFloor=false;
const extraGeom=[];
let vi2=0;
// OBJ face indices are global, so the synthetic slabs must be offset by the number of vertices the
// real geometry already wrote (vOut). Without this base the slabs referenced the first vertices of
// the file and every generated part ended up pointing at the front of the car body.
const vBase=vOut;
const slabNeeded=params.doorway!==false;
if(Object.keys(dbs).length&&slabNeeded){
  const w=params.carWidthBlocks||5;
  for(const name of Object.keys(dbs)){
    const db=dbs[name];
    const zc=(db.mn[2]+db.mx[2])/2; const zh=Math.max(0.4,(db.mx[2]-db.mn[2])/2+0.05);
    const V=[[-w/2,db.mn[1],zc-zh],[w/2,db.mn[1],zc-zh],[w/2,db.mx[1],zc-zh],[-w/2,db.mx[1],zc-zh],[-w/2,db.mn[1],zc+zh],[w/2,db.mn[1],zc+zh],[w/2,db.mx[1],zc+zh],[-w/2,db.mx[1],zc+zh]];
    const Q=[[1,2,3,4],[6,5,8,7],[5,1,4,8],[2,6,7,3],[4,3,7,8],[5,6,2,1]];
    const gname='doorway_'+name;
    doorwayGroups.push(gname);
    const s=['','g '+gname,'usemtl door_mat'];
    V.forEach(v=>s.push('v '+v.map(x=>x.toFixed(5)).join(' ')));
    Q.forEach(q=>s.push('f '+q.map(x=>x+vBase+vi2).join(' ')));
    vi2+=8;
    extraGeom.push(s.join('\n'));
  }
}
// A walkable FLOOR part: without it MTR has no surface inside the car, so a driver can only stand in
// the doorway slabs and can never walk to the cab door. The sill height comes from the door leaves,
// and the slab is inset so players cannot walk through the side walls. FLOOR parts are never rendered.
// floor:true forces the floor even when the model has no passenger doors (a pure locomotive).
const floorNeeded=params.floor!==false;
const forceFloor=params.floor===true;
if(floorNeeded&&(Object.keys(dbs).length||forceFloor)){
  let sill=1e9; for(const n of Object.keys(dbs)) sill=Math.min(sill,dbs[n].mn[1]);
  if(!isFinite(sill)) sill=params.floorHeightBlocks!==undefined?params.floorHeightBlocks:1.0;
  const w=params.carWidthBlocks||5, L=params.carLengthBlocks||15;
  const fx=Math.max(0.5,w/2-0.4), fz=Math.max(0.5,L/2-0.3), fy=sill+0.03, th=0.02;
  const V=[[-fx,fy,-fz],[fx,fy,-fz],[fx,fy+th,-fz],[-fx,fy+th,-fz],[-fx,fy,fz],[fx,fy,fz],[fx,fy+th,fz],[-fx,fy+th,fz]];
  const Q=[[1,2,3,4],[6,5,8,7],[5,1,4,8],[2,6,7,3],[4,3,7,8],[5,6,2,1]];
  const s=['','g floor','usemtl door_mat'];
  V.forEach(v=>s.push('v '+v.map(x=>x.toFixed(5)).join(' ')));
  Q.forEach(q=>s.push('f '+q.map(x=>x+vBase+vi2).join(' ')));
  vi2+=8;
  extraGeom.push(s.join('\n'));
  hasFloor=true;
}
const objFinal=extraGeom.length?objMain+'\n'+extraGeom.join('\n'):objMain;
fs.writeFileSync(path.join(sub,srcBase), objFinal, 'utf8');
// mtl: copy from textureDir, rewrite relative; copy pngs
const texDir=params.textureDir||base;
// The source file may be HST_H.obj while the exported name is lowercased; find the MTL by its
// original base name first, then fall back to the lowercased one.
const origBase=path.basename(params.sourceObj);
const mtlCandidates=[path.join(texDir, origBase.replace(/\.obj$/i,'')+'.mtl'), path.join(texDir, srcBase.replace(/\.obj$/i,'')+'.mtl')];
let mtl='';
for(const candidate of mtlCandidates){ if(fs.existsSync(candidate)){ mtl=fs.readFileSync(candidate,'utf8'); break; } }
const pngNames=[];
mtl=mtl.split('\n').map(l=>{
  const m=/^map_(Kd|d)\s+(.+)$/i.exec(l.trim());
  if(!m) return l;
  const val=m[2].trim().replace(/\\/g,'/');
  const b=val.split('/').pop();
  const src=fs.existsSync(path.join(texDir,b))?path.join(texDir,b):null;
  if(src){ const lower=b.toLowerCase(); pngNames.push([b,lower]); return 'map_'+m[1]+' '+lower; }
  return l;
}).join('\n');
if(!mtl.includes('door_mat')) mtl+= '\nnewmtl door_mat\nKd 1 1 1\n';
fs.writeFileSync(path.join(sub,srcBase.replace(/\.obj$/i,'')+'.mtl'), mtl, 'utf8');
for(const [from,to] of pngNames){ const s=path.join(texDir,from); if(fs.existsSync(s)) fs.copyFileSync(s,path.join(sub,to)); }
// json entry
const length=params.carLengthBlocks||15, width=params.carWidthBlocks||5;
const bc=params.bogieCount||2;
let b1=params.bogieOffsetBlocks, b2=params.bogie2OffsetBlocks;
if(b1===undefined&&bc===2){ b1=-length/2+1.5; b2=length/2-1.5; } if(b1===undefined)b1=0; if(b2===undefined)b2=0;
const custom={vehicles:[{id:id,name:params.name||id,color:params.color||'7FA8CC',transportMode:params.transportMode||'TRAIN',length:length,width:width,bogie1Position:b1,bogie2Position:b2,couplingPadding1:params.couplingPadding1||0,couplingPadding2:params.couplingPadding2||0,models:[{modelResource:'mtr:'+id+'/'+srcBase,textureResource:'minecraft:textures/misc/white.png',modelPropertiesResource:'mtr:properties_'+id+'.json',positionDefinitionsResource:'mtr:definition_'+id+'.json',flipTextureV:params.flipTextureV!==false}]}],signs:[],rails:[],objects:[],lifts:[]};
const parts=[]; const slide=params.doorSlidePx!==undefined?params.doorSlidePx:14;
if(used.body) parts.push({names:['body'],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
if(used.interior) parts.push({names:['interior'],positionDefinitions:['p0'],renderStage:'INTERIOR_TRANSLUCENT',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
// One part per door group: each numbered door slides on its own. Direction defaults to +slide on the
// left / -slide on the right; override per door with params.doorSlideByGroup: {"door_l_2": -14, ...}.
for(const g of doorGroups){
  const isLeft=g.startsWith('door_l');
  const ov=params.doorSlideByGroup&&params.doorSlideByGroup[g]!==undefined?params.doorSlideByGroup[g]:undefined;
  parts.push({names:[g],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:ov!==undefined?ov:(isLeft?slide:-slide),doorAnimationType:params.doorAnimationType||'STANDARD'});
}
for(const g of doorwayGroups) parts.push({names:[g],positionDefinitions:['p0'],type:'DOORWAY'});
if(hasFloor) parts.push({names:['floor'],positionDefinitions:['p0'],type:'FLOOR'});
// The cab door(s) are rendered as plain EXTERIOR parts (they are anchors too, see mmtr_anchors_*.json).
// Give them a slide axis only when the model asks for it via doorSlideByGroup, otherwise they stay put.
for(const g of cabDoorParts){
  const ov=params.doorSlideByGroup&&params.doorSlideByGroup[g]!==undefined?params.doorSlideByGroup[g]:undefined;
  parts.push({names:[g],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:ov!==undefined?ov:0,doorAnimationType:params.doorAnimationType||'STANDARD'});
}
// extras (matched groups beyond known) as EXTERIOR - "anchor" is data, never rendered
for(const r of Object.keys(used)) if(!['body','interior','door_l','door_r','anchor'].includes(r)) parts.push({names:[r],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
const props={modelYOffset:0,parts:parts};
const defs={positionDefinitions:[{name:'p0',positions:[{}],positionsFlipped:[]}]};
fs.writeFileSync(path.join(aMtr,'mtr_custom_resources.json'), JSON.stringify(custom));
fs.writeFileSync(path.join(aMtr,'properties_'+id+'.json'), JSON.stringify(props));
fs.writeFileSync(path.join(aMtr,'definition_'+id+'.json'), JSON.stringify(defs));
// MMTR cab HUD anchors (pure 2D panel plane): written next to the MTR json, read by the client HUD.
// The same file carries the model's dashboard layout ("hud"), because the panel image is per MODEL:
// different rolling stock has different instrument desks, while the two ends of one car share one
// layout. params.hud is the inline layout, params.hudLayout is a path to a JSON file with one; with
// neither, the default (centred speed + km/h) is written so every model stays editable in the pack.
const DEFAULT_HUD_LAYOUT={
  background:'#FF05080C',
  widgets:[
    {kind:'roundRect',x:0.015,y:0.06,w:0.97,h:0.88,radius:0.08,color:'#FF0E141C'},
    {kind:'speed',x:0.5,y:0.60,size:0.52,color:'#FFFFFFFF',align:'center'},
    {kind:'text',text:'km/h',x:0.5,y:0.19,size:0.20,color:'#FFD2E6F7',align:'center'}
  ]
};
let hudLayout=params.hud||null;
const hudLayoutPath=params.hudLayout||null;
if(!hudLayout&&hudLayoutPath){
  if(!fs.existsSync(hudLayoutPath)) throw new Error('hudLayout not found: '+hudLayoutPath);
  const parsed=JSON.parse(fs.readFileSync(hudLayoutPath,'utf8'));
  hudLayout=parsed.hud||parsed;
}
if(anchors.length){
  const anchorFile={anchors:anchors,hud:hudLayout||DEFAULT_HUD_LAYOUT};
  fs.writeFileSync(path.join(aMtr,'mmtr_anchors_'+id+'.json'), JSON.stringify(anchorFile));
  console.log('hud layout: '+(hudLayout?'authored':'default')+' widgets='+(anchorFile.hud.widgets||[]).length);
}
console.log('anchors:', JSON.stringify(anchors));
fs.writeFileSync(path.join(stage,'pack.mcmeta'), JSON.stringify({pack:{pack_format:params.packFormat||18,description:(params.name||id)+' auto pack'}}));
// zip
// Written here rather than with Compress-Archive: Windows PowerShell 5.1's Compress-Archive stores
// entries with "\" separators, which Java/Minecraft cannot resolve as resource paths - the pack then
// loads with no models at all. Entry names must always use "/".
const zipOut=path.join(params.outputDir||base,(params.outputPackName||id)+'.zip');
if(fs.existsSync(zipOut)) fs.unlinkSync(zipOut);
writeZip(stage,zipOut);
console.log('zip',zipOut,fs.statSync(zipOut).size+' bytes');
console.log('roles:', JSON.stringify(Object.keys(used)));
