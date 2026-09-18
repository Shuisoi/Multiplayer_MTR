// MMTR OBJ vehicle packager v1 — reads vehicle.json, normalizes Blender OBJ -> MTR resource pack zip
const fs=require('fs');
const path=require('path');
const {spawnSync}=require('child_process');
const {writeZip}=require('./zip.js');
const {resolveRoot}=require('./paths.js');
const params=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
// 配置里的路径可以写 ${MC_ROOT} 占位符（见 paths.js），这样配置文件能入库而不带机器相关绝对路径。
for(const key of ['sourceObj','textureDir','outputDir','extraAnchors','stagingDir','soundDir','hudLayout']){
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
// The model file name may contain AT MOST ONE dot (the extension). MTR builds the resource path with
// CustomResourceTools.formatIdentifier, which does `identifierString.split(".")[0]` and then appends the
// extension - so "saf101v2.0.obj" is looked up as "saf101/saf101v2.obj", which does not exist. The pack
// then builds fine, passes every zip check, and the model silently never loads (`obj=0 chars` in the
// client log, and "0/N part conditions have optimized geometry" while rendering). Rename the file.
{
  const extraDots=(path.basename(params.sourceObj).match(/\./g)||[]).length-1;
  if(extraDots>0){
    console.warn('WARNING: sourceObj base name "'+path.basename(params.sourceObj)+'" contains '+extraDots+' extra dot(s).');
    console.warn('         MTR truncates the resource path at the FIRST dot, so it will look for "'+
      path.basename(params.sourceObj).split('.')[0]+'.'+path.extname(params.sourceObj).slice(1)+'"');
    console.warn('         and read 0 chars - the model will NOT display. Rename it to a single dot.');
  }
}
// Sanity check the source before anything else: an OBJ written without "Write Normals" / "Include UVs"
// still parses, still packages, and still validates - it just renders as a completely untextured black
// blob in game, because every face's vt/vn index points at nothing. That failure is invisible at build
// time and expensive to diagnose in game, so it is called out here. (Blender's Wavefront exporter has
// these as SEPARATE checkboxes from "Write Materials"; leaving them off is the usual cause.)
{
  const attr={v:0,vt:0,vn:0,f:0};
  for(const line of raw.split('\n')){
    const t=line.trim();
    if(t.startsWith('v '))attr.v++;
    else if(t.startsWith('vt '))attr.vt++;
    else if(t.startsWith('vn '))attr.vn++;
    else if(t.startsWith('f '))attr.f++;
  }
  if(attr.f>0&&attr.vt===0){
    console.warn('WARNING: '+params.sourceObj+' has '+attr.f+' faces but NO "vt" UV lines.');
    console.warn('         The exported pack will render UNTEXTURED/black. Re-export with "Include UVs".');
  }
  if(attr.f>0&&attr.vn===0){
    console.warn('WARNING: '+params.sourceObj+' has '+attr.f+' faces but NO "vn" normal lines.');
    console.warn('         Lighting will be wrong. Re-export with "Write Normals".');
  }
  console.log('source obj: v='+attr.v+' vt='+attr.vt+' vn='+attr.vn+' f='+attr.f);
}
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
// A pattern in groupMap can be a SUBSTRING of another role's pattern, and roleOf() picks the LONGEST
// match. That silently steals names from the other role: adding "glass": ["windshield"] makes
// "mmtr_windshield_1" match "windshield" (9 chars) over the anchor prefix "mmtr_" (5), so the windshield
// stops being an anchor - no error, just two missing anchors and no wipers in game. Warn about the
// overlap instead of letting it happen quietly.
{
  // roleOf() picks the LONGEST matching pattern. Names are prefixed with the anchor pattern (usually
  // "mmtr_"), so the dangerous case is narrow and specific: a NON-anchor pattern that reproduces the
  // word an anchor name use AFTER the prefix - the anchor KINDS. For example with
  // "glass": ["windshield"], the object "mmtr_windshield_1" matches "windshield" (9 chars) over the
  // anchor prefix "mmtr_" (5), so it silently stops being an anchor: no error, two missing anchors,
  // and no wipers in game. Patterns like "BlockEntities" cannot do this, because no anchor is named
  // "mmtr_BlockEntities" - so they are not reported.
  const ANCHOR_KINDS=['hud','seat','cabdoor','ack','windshield','door'];
  const patterns=[];
  for(const role of Object.keys(groupMap)) for(const pat of (groupMap[role]||[])) patterns.push({role:role,pat:String(pat)});
  for(const other of patterns){
    if(other.role==='anchor') continue;
    for(const kind of ANCHOR_KINDS){
      if(other.pat===kind||other.pat.indexOf(kind)>=0||kind.indexOf(other.pat)>=0){
        // Longer than the anchor prefix? Then it wins and the anchor vanishes.
        const anchorLength=Math.min(...patterns.filter(p=>p.role==='anchor').map(p=>p.pat.length));
        if(other.pat.length>anchorLength){
          console.warn('WARNING: groupMap role "'+other.role+'" pattern "'+other.pat+'" swallows the anchor kind "'+kind+'".');
          console.warn('         An object named "mmtr_'+kind+'" would match "'+other.pat+'" ('+other.pat.length+' chars)');
          console.warn('         over the anchor prefix ('+anchorLength+' chars), so it becomes a VISIBLE "'+other.role+'" part');
          console.warn('         and the ANCHOR DISAPPEARS. Rename the object or the pattern so they do not overlap.');
        }
      }
    }
  }
}
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
//
// A mmtr_hud* object is normally ONE flat quad, and that is all the client needs. A dashboard can
// however be a FOLDED surface - several faces meeting at a crease - and one flat quad cannot follow
// it: the quad would sit at the average of every vertex, be sized to the whole folded extent, and
// end up half buried in the shell and half floating in front of it.
//
// So a multi-face hud group ALSO emits one FACET per face, each with its own frame and its true
// in-plane size, plus a uv sub-rectangle into ONE shared canvas. The canvas is the surface
// UNFOLDED about the crease: a facet's coordinate along the folding direction is its own in-plane
// distance from the crease, accumulated from the reference facet along the chain of SHARED EDGES,
// so two neighbouring facets agree exactly on the crease they share. That is what makes the painted
// image continuous across the fold (a poster folded along its edge) instead of squashed.
//
// The group-level x/y/z + normal/up/right + widthM/heightM keep their old meaning - the reference
// face, projected over every vertex - so a single-face model is byte-identical to before, and a
// group that is not a usable folded surface (a box, a broken chain, a twisted pair) emits no facet
// data at all and falls back to that same old quad.
const MAX_FOLD_DEG = 89;       // past this the "unfold" is meaningless - and it means the group is a box
const MAX_FACET_COUNT = 16;    // more faces than this is not a dashboard
const ALIGN_DOT = 0.999;       // |dot(a, b)| above this counts as "the same axis"

const rc=i=>{ const v=vpos[i-1]; return [v[0]-cx, v[1], v[2]-cz]; };
const vSub=(a,c)=>[a[0]-c[0], a[1]-c[1], a[2]-c[2]];
const vAdd=(a,b)=>[a[0]+b[0], a[1]+b[1], a[2]+b[2]];
const vScl=(a,s)=>[a[0]*s, a[1]*s, a[2]*s];
const vCross=(a,b)=>[a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
const vNorm=v=>{ const l=Math.hypot(v[0],v[1],v[2])||1; return [v[0]/l,v[1]/l,v[2]/l]; };
const vDot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
const vRound=(v,d)=>v.map(x=>+x.toFixed(d));
const vAverage=pts=>{ const s=[0,0,0]; for(const p of pts){ s[0]+=p[0]; s[1]+=p[1]; s[2]+=p[2]; } return vScl(s,1/pts.length); };

// Newell normal + area, measured about the polygon's own centroid so the result does not depend on
// where the group origin happens to be. Returns a unit normal (or +Z for a degenerate polygon).
const faceNormalArea=points=>{
  let nx=0,ny=0,nz=0;
  for(let i=0;i<points.length;i++){ const a=points[i], b=points[(i+1)%points.length]; nx+=(a[1]-b[1])*(a[2]+b[2]); ny+=(a[2]-b[2])*(a[0]+b[0]); nz+=(a[0]-b[0])*(a[1]+b[1]); }
  const area=Math.hypot(nx,ny,nz)/2;
  return {n: area>0 ? vNorm([nx,ny,nz]) : [0,0,1], area:area};
};

// The packager's own frame rule: "up" is the face edge with the largest |Y|, orthogonalised
// against the normal; "right" completes the right-handed frame (right = up x normal).
const faceFrame=(points,normal)=>{
  const e1=vSub(points[1],points[0]), e2=vSub(points[2],points[1]);
  const upRaw=Math.abs(e1[1])>=Math.abs(e2[1]) ? e1 : e2;
  const up=vNorm(vSub(upRaw,vScl(normal,vDot(upRaw,normal))));
  return {up:up, right:vNorm(vCross(up,normal))};
};

/**
 * Per-facet data of a FOLDED mmtr_hud* group, or null when the group is not a usable folded
 * surface and the old single quad should be used instead.
 *
 * @param g   the anchor group ({name, faces} with faces = arrays of OBJ vertex indices)
 * @param ctx {kind, refFace, refNormal, refUp, refRight, groupCentroid, flip, rawName}
 */
function buildFacets(g,ctx){
  if(ctx.kind!=='hud' || g.faces.length<2) return null;
  if(g.faces.length>MAX_FACET_COUNT){ console.warn('WARNING: '+(g.name||'mmtr_hud')+' has '+g.faces.length+' faces; a dashboard that folded is not - no facet data, the old single quad is used.'); return null; }

  const facets=[];
  for(const f of g.faces){
    if(f.length<3) return null;
    if(f.length!==4) console.warn('WARNING: a face of '+(g.name||'mmtr_hud')+' has '+f.length+' vertices; a HUD face must be a QUAD, otherwise the rectangle the client draws will not fit it.');
    const points=f.map(rc);
    const centre=vAverage(points);
    const raw=faceNormalArea(points);
    if(raw.area<=0) return null;
    // The same winding decision the group normal gets, so every facet faces the same way.
    const normal=ctx.flip ? vScl(raw.n,-1) : raw.n;
    const frame=faceFrame(points,normal);
    let wMin=Infinity,wMax=-Infinity,hMin=Infinity,hMax=-Infinity;
    for(const p of points){
      const d=vSub(p,centre), dr=vDot(d,frame.right), dh=vDot(d,frame.up);
      if(dr<wMin)wMin=dr; if(dr>wMax)wMax=dr;
      if(dh<hMin)hMin=dh; if(dh>hMax)hMax=dh;
    }
    facets.push({
      ids:f, points:points, area:raw.area,
      // Placed by the bounding-box CENTRE, not the vertex average, so an irregular quad is still
      // covered by the rectangle the client draws.
      position:vAdd(centre,vAdd(vScl(frame.right,(wMin+wMax)/2),vScl(frame.up,(hMin+hMax)/2))),
      normal:normal, up:frame.up, right:frame.right,
      widthM:wMax-wMin, heightM:hMax-hMin, off:0
    });
  }

  const refIndex=g.faces.indexOf(ctx.refFace);
  if(refIndex<0) return null;
  const ref=facets[refIndex];
  // Everything below measures against the REFERENCE FACET'S OWN frame, not the group frame. The
  // group frame comes from a three-vertex cross about the average of EVERY vertex of the group, so
  // on a folded group its normal is off the reference plane by a couple of degrees (measured: 2.7
  // degrees on a 45-degree fold). That is harmless for the legacy single quad but fatal for the
  // axis test, which asks whether all facets share an axis to within 0.1%.
  // Safety: the facet normal comes from the face winding while the group normal was built from a
  // three-vertex cross, so they must agree in sign. If they do not, the mesh winding is not
  // consistent and every facet's "front" would be a guess.
  if(vDot(ref.normal,ctx.refNormal)<0.9){ console.warn('WARNING: '+(g.name||'mmtr_hud')+' face winding disagrees with the anchor normal; no facet data.'); return null; }

  let maxFold=0,maxDev=0;
  for(const f of facets){
    maxFold=Math.max(maxFold,Math.acos(Math.max(-1,Math.min(1,vDot(f.normal,ref.normal))))*180/Math.PI);
    for(const p of f.points) maxDev=Math.max(maxDev,Math.abs(vDot(vSub(p,ref.position),ref.normal)));
  }
  if(maxFold>MAX_FOLD_DEG){ console.warn('WARNING: '+(g.name||'mmtr_hud')+' contains faces '+maxFold.toFixed(0)+' degrees apart (a box, not a folded surface); no facet data, the old single quad is used.'); return null; }
  // Coplanar faces (a quad exported as two triangles, say): the old single quad is already exact.
  if(maxFold<1.5 && maxDev<0.002) return null;

  // A fold about `right` keeps `right` and turns `up`; a fold about `up` does the opposite. Anything
  // else (two axes turning at once) is a twisted surface, which cannot be unfolded into one canvas.
  const shareRight=facets.every(f=>Math.abs(vDot(f.right,ref.right))>ALIGN_DOT);
  const shareUp=facets.every(f=>Math.abs(vDot(f.up,ref.up))>ALIGN_DOT);
  const foldAboutRight=shareRight&&!shareUp;
  const foldAboutUp=shareUp&&!shareRight;
  if(!foldAboutRight&&!foldAboutUp){ console.warn('WARNING: '+(g.name||'mmtr_hud')+' facets do not share a fold axis (a twisted surface); no facet data, the old single quad is used.'); return null; }
  const localAxis=f=>foldAboutRight ? f.up : f.right;

  // Chain of shared edges, and the offset recurrence. A facet's local coordinate is measured from
  // its own quad centre along the folding axis: L(f, x) = dot(x - f.position, localAxis(f)) + f.off.
  // The shared crease is perpendicular to the folding axis, so L is CONSTANT along it on both sides
  // and continuity reduces to matching that constant.
  const edgeKey=(a,b)=>a<b ? a+'_'+b : b+'_'+a;
  const edges=new Map();
  facets.forEach((f,fi)=>{
    const ids=[...new Set(f.ids)];
    for(let i=0;i<ids.length;i++){
      const key=edgeKey(ids[i],ids[(i+1)%ids.length]);
      if(!edges.has(key)) edges.set(key,[]);
      edges.get(key).push(fi);
    }
  });
  const neighboursOf=fi=>{
    const out=[], ids=[...new Set(facets[fi].ids)];
    for(let i=0;i<ids.length;i++) for(const j of (edges.get(edgeKey(ids[i],ids[(i+1)%ids.length]))||[])) if(j!==fi&&!out.includes(j)) out.push(j);
    return out;
  };

  const visited=new Set([refIndex]), queue=[refIndex];
  while(queue.length){
    const i=queue.shift();
    for(const j of neighboursOf(i)){
      if(visited.has(j)) continue;
      const idSet=new Set(facets[i].ids);
      const shared=facets[j].ids.filter(x=>idSet.has(x));
      if(shared.length<2) continue;
      const X=vAverage(shared.map(rc));
      facets[j].off=facets[i].off
        +vDot(vSub(X,facets[i].position),localAxis(facets[i]))
        -vDot(vSub(X,facets[j].position),localAxis(facets[j]));
      visited.add(j); queue.push(j);
    }
  }
  if(visited.size!==facets.length){ console.warn('WARNING: '+(g.name||'mmtr_hud')+' facets are not one connected chain of shared edges; no facet data, the old single quad is used.'); return null; }

  // The shared axis is ONE affine function over the whole group, hence continuous by construction;
  // the folding axis comes from each facet's local coordinate plus its accumulated offset.
  const sharedCoord=p=>foldAboutRight ? vDot(vSub(p,ref.position),ref.right) : vDot(vSub(p,ref.position),ref.up);
  const foldCoord=(f,p)=>vDot(vSub(p,f.position),localAxis(f))+f.off;
  let sharedMin=Infinity,sharedMax=-Infinity,foldMin=Infinity,foldMax=-Infinity;
  for(const f of facets) for(const p of f.points){
    const u=sharedCoord(p), s=foldCoord(f,p);
    if(u<sharedMin)sharedMin=u; if(u>sharedMax)sharedMax=u;
    if(s<foldMin)foldMin=s; if(s>foldMax)foldMax=s;
  }
  const sharedSpan=sharedMax-sharedMin, foldSpan=foldMax-foldMin;
  if(sharedSpan<=1e-6||foldSpan<=1e-6) return null;

  const faces=facets.map(f=>{
    let sharedMin2=Infinity,sharedMax2=-Infinity,sMin=Infinity,sMax=-Infinity;
    for(const p of f.points){
      const s=foldCoord(f,p), u=sharedCoord(p);
      if(s<sMin)sMin=s; if(s>sMax)sMax=s;
      if(u<sharedMin2)sharedMin2=u; if(u>sharedMax2)sharedMax2=u;
    }
    // u runs left to right in image space, so it is just the normalized shared coordinate (or the
    // normalized folding coordinate, when the fold is about `up`).
    const uA=(sharedMin2-sharedMin)/sharedSpan, uB=(sharedMax2-sharedMin)/sharedSpan;
    const sA=(sMin-foldMin)/foldSpan, sB=(sMax-foldMin)/foldSpan;
    // v runs TOP TO BOTTOM in image space: row 0 of the painted canvas is the top of the panel, and
    // the client puts texture v=0 on the quad's +Y edge. So the folding coordinate - which grows
    // along each facet's own "up" - has to be flipped, or the dashboard reads upside down.
    const vA=1-sB, vB=1-sA;
    const face=foldAboutRight
      ? {u0:uA, u1:uB, v0:vA, v1:vB}
      : {u0:sA, u1:sB, v0:vA, v1:vB};
    return {
      x:+f.position[0].toFixed(5), y:+f.position[1].toFixed(5), z:+f.position[2].toFixed(5),
      normal:vRound(f.normal,6), up:vRound(f.up,6), right:vRound(f.right,6),
      widthM:+f.widthM.toFixed(4), heightM:+f.heightM.toFixed(4),
      u0:+face.u0.toFixed(6), v0:+face.v0.toFixed(6), u1:+face.u1.toFixed(6), v1:+face.v1.toFixed(6)
    };
  });

  console.log('hud facets: '+ctx.rawName+' faces='+faces.length+' fold='+maxFold.toFixed(1)+'deg about '+(foldAboutRight?'right':'up')
    +' canvas='+(foldAboutRight?sharedSpan:foldSpan).toFixed(3)+' x '+(foldAboutRight?foldSpan:sharedSpan).toFixed(3)+'m');
  return {
    canvasWidthM:+((foldAboutRight ? sharedSpan : foldSpan)).toFixed(4),
    canvasHeightM:+((foldAboutRight ? foldSpan : sharedSpan)).toFixed(4),
    faces:faces
  };
}

function buildAnchors(){
  const anchors=[];
  const sub=vSub, cr=vCross, nz=vNorm, dot=vDot;
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
    // mmtr_windshield[_<cab>]: the rain/wiper plane. <cab> means a CAB NUMBER, exactly as it does for
    // hud/seat/cabdoor - a double-ended locomotive must be able to say which cab a screen belongs to.
    // The wiper's pivot and park direction come from the quad's own geometry (centre and "right" edge),
    // so there is no second index to spend here.
    const wsm=/^windshield(?:_(\d+))?$/.exec(rawName);
    const kind=wsm?'windshield':(m?m[1]:rawName);
    const cab=wsm?(wsm[1]?+wsm[1]:1):(m&&m[2]?+m[2]:null);
    const door=wsm?null:(m&&m[3]?+m[3]:null);
    const anchor={name:rawName,kind:kind,cab:cab,door:door,car:params.carIndex||0,
      x:+c[0].toFixed(5), y:+c[1].toFixed(5), z:+c[2].toFixed(5),
      normal:n.map(x=>+x.toFixed(6)), up:up.map(x=>+x.toFixed(6)), right:right.map(x=>+x.toFixed(6)),
      widthM:+(wMax-wMin).toFixed(4), heightM:+(hMax-hMin).toFixed(4)};
    // A folded dashboard adds canvasWidthM/canvasHeightM + one entry per face. The fields above keep
    // their old meaning, so a client that does not know about facets still draws the old quad.
    const facetFields=buildFacets(g,{kind:kind,refFace:f0,refNormal:n,refUp:up,refRight:right,groupCentroid:c,flip:flipThis,rawName:rawName});
    if(facetFields) for(const key of Object.keys(facetFields)) anchor[key]=facetFields[key];
    anchors.push(anchor);
  }
  return anchors;
}
const anchors=buildAnchors();
// MMTR windshield: the rain/wiper plane(s). The client draws a procedurally-generated precipitation
// layer on the mmtr_windshield face and sweeps a wiper arm over it. The client owns all the defaults,
// so a model with a bare mmtr_windshield still works - this block only forwards what the config says.
//
// params.windshield accepts BOTH shapes:
//   keyed object (natural, and what consist/newstock.json uses):
//       "windshield": { "windshield_1": { "sweepSign": -1 }, "windshield_2": {} }
//   array (for when the key has to be derived):
//       "windshield": [ { "anchor": "mmtr_windshield_1", ... }, { "index": 2, ... } ]
// Every field the client reads is listed below; an unknown field is dropped rather than silently ignored.
//
// KEEP THIS IN STEP WITH MmtrWindshield.WindshieldConfig. A field the client reads but this list omits is
// DROPPED AT PACK TIME and the config looks like it was ignored in game - that was the real cause of the
// "sweepSign has no effect" bug (notes/179 §9.4 #3), and the "droplet physics has no effect" repeat of it.
const WINDSHIELD_FIELDS=['raindrops','fallMps','maxStreakM','wiper','dualWiper','armM','parkAngleDeg',
                         'sweepDeg','sweepSign','periodS','pivotU','pivotV','bladeWidthM','colour','armColour','snow','twoSided',
                         'creepMps','jitterMps','minBeadRadiusM','maxBeadRadiusM','growthMps','spawnPerSecond'];
function buildWindshieldConfig(){
  const byAnchor={};
  const put=(key,entry)=>{
    const values={};
    for(const field of WINDSHIELD_FIELDS){
      if(entry&&entry[field]!==undefined) values[field]=entry[field];
    }
    // Name anything that is about to be dropped, so the next new field is caught here instead of in game.
    for(const field of Object.keys(entry||{})){
      if(field!=='anchor'&&field!=='index'&&!WINDSHIELD_FIELDS.includes(field)){
        console.warn(`windshield["${key}"]: unknown field "${field}" will NOT reach the client - add it to WINDSHIELD_FIELDS if it is meant to do something`);
      }
    }
    byAnchor[key]=values;
  };
  const ws=params.windshield;
  if(ws!==undefined&&ws!==null){
    if(Array.isArray(ws)){
      for(const entry of ws){
        let key=null;
        if(entry&&entry.anchor){
          const mn=/^mmtr_(windshield(?:_\d+)?)$/.exec(String(entry.anchor));
          if(mn) key=mn[1];
        }
        if(!key&&entry&&entry.index!==undefined) key='windshield_'+entry.index;
        if(!key){ console.warn('windshield array entry has no usable anchor/index, ignored'); continue; }
        put(key,entry);
      }
    } else {
      // Keyed by anchor name, with or without the "mmtr_" prefix.
      for(const rawKey of Object.keys(ws)){
        const mn=/^(?:mmtr_)?(windshield(?:_\d+)?)$/.exec(String(rawKey));
        if(!mn){ console.warn('windshield key "'+rawKey+'" is not a windshield anchor name, ignored'); continue; }
        put(mn[1],ws[rawKey]);
      }
    }
  }
  // Models that have windshields but no authored block still get an entry, so the file shows where to tune.
  for(const anchor of anchors){
    if(anchor.kind==='windshield'&&!byAnchor[anchor.name]) byAnchor[anchor.name]={};
  }
  return byAnchor;
}
const windshieldConfig=buildWindshieldConfig();
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
    const wsm=/^windshield(?:_(\d+))?$/.exec(rawName);
    anchors.push({name:rawName,kind:a.kind||(wsm?'windshield':(m?m[1]:rawName)),cab:a.cab!==undefined?a.cab:(wsm?(wsm[1]?+wsm[1]:1):(m&&m[2]?+m[2]:null)),door:a.door!==undefined?a.door:(wsm?null:(m&&m[3]?+m[3]:null)),car:params.carIndex||0,
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

// ---- Sound (BVE): params.soundBase names the set, params.soundDir holds its source files -------
// The game builds every sound-event id as "<soundBase>_<value>" where <value> is the file name a
// sound.cfg line points at with its extension stripped (BveVehicleSoundConfig.audioBaseName +
// BveConfigFile). The files themselves live at assets/mtr/sounds/<soundBase>/<value>.ogg, so a NEW
// sound set must ALSO register those ids in assets/mtr/sounds.json - Minecraft's SoundSystem logs
// "Unable to play unknown soundEvent" and silently drops anything unregistered. Stock MTR sets ship
// pre-registered ids, which is why packs that only replace their .ogg files need no sounds.json.
const soundBase=params.soundBase||null;
const soundNames={};
if(soundBase){
  const soundSrc=params.soundDir||path.join(MTR_ROOT,'assets','sounds',soundBase);
  if(!fs.existsSync(soundSrc)) throw new Error('soundDir not found: '+soundSrc);
  const destDir=path.join(aMtr,'sounds',soundBase);
  fs.mkdirSync(destDir,{recursive:true});
  // Only real .ogg files become events: [MTR] scalars (DoorCloseSoundLength=1, RegenerationLimit=7.5)
  // and [Motor] / [Run] indices are numeric keys, not file names, and must not be registered.
  const available=new Set();
  for(const entry of fs.readdirSync(soundSrc,{withFileTypes:true})){
    if(entry.isFile()){
      fs.copyFileSync(path.join(soundSrc,entry.name),path.join(destDir,entry.name));
      if(/\.ogg$/i.test(entry.name)) available.add(entry.name.toLowerCase().replace(/\.ogg$/i,''));
    }
  }
  // Register one event per name a sound.cfg line points at (mirrors BveConfigFile's parsing: strip
  // comments, lower-case, drop the path prefix and any ".wav" suffix - the shipped files are .ogg).
  const cfgPath=path.join(soundSrc,'sound.cfg');
  if(!fs.existsSync(cfgPath)) throw new Error('sound.cfg not found in soundDir: '+soundSrc);
  for(const line of fs.readFileSync(cfgPath,'utf8').split(/[\r\n]+/)){
    const clean=line.trim().replace(/\s*(;|#|\/\/).+$/,'');
    const m=/^(.+?)=(.*)$/.exec(clean);
    if(!m) continue;
    const name=m[2].trim().toLowerCase().replace(/\\/g,'/').replace(/\.wav|\s|.+\//g,'');
    if(name&&available.has(name)) soundNames[soundBase+'_'+name]='mtr:'+soundBase+'/'+name;
  }
}
// json entry
const length=params.carLengthBlocks||15, width=params.carWidthBlocks||5;
const bc=params.bogieCount||2;
let b1=params.bogieOffsetBlocks, b2=params.bogie2OffsetBlocks;
if(b1===undefined&&bc===2){ b1=-length/2+1.5; b2=length/2-1.5; } if(b1===undefined)b1=0; if(b2===undefined)b2=0;
const custom={vehicles:[{id:id,name:params.name||id,color:params.color||'7FA8CC',transportMode:params.transportMode||'TRAIN',length:length,width:width,bogie1Position:b1,bogie2Position:b2,couplingPadding1:params.couplingPadding1||0,couplingPadding2:params.couplingPadding2||0,bveSoundBaseResource:soundBase||undefined,models:[{modelResource:'mtr:'+id+'/'+srcBase,textureResource:'minecraft:textures/misc/white.png',modelPropertiesResource:'mtr:properties_'+id+'.json',positionDefinitionsResource:'mtr:definition_'+id+'.json',flipTextureV:params.flipTextureV!==false}]}],signs:[],rails:[],objects:[],lifts:[]};
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
  const anchorFile={anchors:anchors,hud:hudLayout||DEFAULT_HUD_LAYOUT,windshield:windshieldConfig};
  fs.writeFileSync(path.join(aMtr,'mmtr_anchors_'+id+'.json'), JSON.stringify(anchorFile));
  console.log('hud layout: '+(hudLayout?'authored':'default')+' widgets='+(anchorFile.hud.widgets||[]).length);
}
console.log('anchors:', JSON.stringify(anchors));
if(soundBase){
  const soundJson={};
  for(const event in soundNames){
    soundJson[event]={sounds:[{name:soundNames[event],attenuation_distance:32}]};
  }
  fs.writeFileSync(path.join(aMtr,'sounds.json'), JSON.stringify(soundJson));
  console.log('sound set: '+soundBase+' events='+Object.keys(soundJson).length);
}
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
