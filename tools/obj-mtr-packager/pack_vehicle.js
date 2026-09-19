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
  const ANCHOR_KINDS=['hud','seat','cabdoor','ack','windshield','wipersweep','door'];
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
// Anchor kinds that must ALWAYS be anchors, even when some groupMap role happens to spell the same
// word. Concretely: a role for the solid wiper part ("wiper": ["wiper"]) would otherwise win over the
// 5-char "mmtr_" prefix inside "mmtr_wipersweep_1_1" (a 5-char pattern ties, and the tie is broken by
// object key order) - the sweep anchor would silently turn into a VISIBLE part and vanish as data.
//
// "door" is DELIBERATELY absent from this list: models in this repo name passenger doors
// mmtr_door_l_1 (HST_B, p1) and rely on the longer "door_l" pattern beating the anchor prefix.
const RESERVED_ANCHOR_KINDS=['hud','seat','cabdoor','ack','windshield','wipersweep'];
const anchorPrefixes=(groupMap.anchor||[]).map(String);
function isReservedAnchorName(name){
  for(const prefix of anchorPrefixes){
    if(prefix.length===0||name.indexOf(prefix)!==0) continue;
    const rest=name.slice(prefix.length);
    if(RESERVED_ANCHOR_KINDS.some(kind=>rest===kind||rest.indexOf(kind+'_')===0)) return true;
  }
  return false;
}
// Door groups keep their own names so every door is a separate MTR part (its own slide + DOORWAY):
//   door_l_1 / door_l_2 ... = 左侧第1/2扇客车门;  door_r_1 / door_r_2 ... = 右侧
//   mmtr_cabdoor_<cab>_<n> = 驾驶室 <cab>(1=A端,2=B端) 的第 <n> 扇司机门 (anchor role, not rendered)
const doorGroups=[];
const anchorFaces=[]; let currentRole=null; let currentAnchor=null;
// mmtr_cabdoor_* is special: it is BOTH a visible part (the driver's door you can see and aim at)
// and an anchor. Other mmtr_* faces (hud/seat/ack) are pure data and get stripped from the geometry.
const cabDoorParts=[];
// wiper_<cab>_<pane>: the solid wiper's own visible parts, each named and modelled INDEPENDENTLY -
// wiper_ (the blade), wiperarm_ (the arm), wiperrod_ (the control rod of a parallel linkage). Kept one
// part per object so each can be rotated about its own pivot, and collected here so their geometry can
// be fitted into the mechanism (see fitWiperMechanism).
const wiperGroups=[];
const partFaces=[]; let currentPart=null;
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
    const raw2=renameGroup(t.slice(2).trim().replace(/\.\d+$/,'')); let cn=canonical(raw2);
    if(cn!=='anchor'&&isReservedAnchorName(raw2)){
      // Say so: the groupMap overlap check above cannot catch this one (a 5-char "wiper" pattern does
      // not out-length the 5-char "mmtr_" prefix, so it passes silently) - and without this override
      // the anchor would have been rendered as a part and lost as data.
      console.warn('WARNING: groupMap role "'+cn+'" also matches the anchor name "'+raw2+'"; the anchor wins. '+
        'Rename the role pattern so it does not appear inside an anchor name.');
      cn='anchor'; used.anchor=1;
    }
    if(cn==='anchor'){
      const cabDoorMatch=/^mmtr_cabdoor_/i.test(raw2);
      currentAnchor={name:raw2,faces:[]};
      anchorFaces.push(currentAnchor);
      currentPart=null;
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
    else if(cn==='door_l'||cn==='door_r'){ if(!doorGroups.includes(raw2)) doorGroups.push(raw2); currentRole=raw2; currentAnchor=null; currentPart=null; out.push('g '+raw2); }
    // Solid wiper parts, each modelled and named INDEPENDENTLY and each kept as its OWN part (so each
    // can be rotated about its own pivot later): wiper_ = the blade, wiperarm_ = the arm, wiperrod_ =
    // the control rod of a parallel linkage. Recognised by name, so no groupMap entry is needed - and
    // a generic role would both merge them into one part and steal names from mmtr_wipersweep_*.
    else if(/^wiper(arm|rod)?_\d+_\d+$/i.test(raw2)){
      if(!wiperGroups.includes(raw2)) wiperGroups.push(raw2);
      currentPart={name:raw2,faces:[]};
      partFaces.push(currentPart);
      currentRole=raw2; currentAnchor=null; out.push('g '+raw2);
    }
    else if(cn){ currentRole=cn; currentAnchor=null; currentPart=null; out.push('g '+cn); }
    else { currentRole=null; currentAnchor=null; currentPart=null; }
  }
  else if(t.startsWith('f ')){
    // Anchor faces are always collected (even when the group is also rendered, like the cab door);
    // data-only anchor geometry is not written out at all.
    const indices=t.split(/\s+/).slice(1).map(x=>+x.split('/')[0]);
    if(currentAnchor){ currentAnchor.faces.push(indices); }
    if(currentPart){ currentPart.faces.push(indices); }
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

  const canvasWidthM=+(foldAboutRight ? sharedSpan : foldSpan).toFixed(4);
  const canvasHeightM=+(foldAboutRight ? foldSpan : sharedSpan).toFixed(4);
  // The 2D DOMAIN mapper: any 3D point on the surface -> (right, up) in metres from the reference
  // facet's centre, measured in the UNROLLED frame. This is the space a wiper's pivot and angles live
  // in, so a sweep fan drawn on a curved glass fits in the same coordinates the drops are simulated in.
  // A point is assigned to the facet it is closest to (by distance from that facet's plane).
  const domain={
    canvasWidthM: canvasWidthM,
    canvasHeightM: canvasHeightM,
    toRightUp: p => {
      let best=facets[0], bestDistance=Infinity;
      for(const f of facets){
        const distance=Math.abs(vDot(vSub(p,f.position),f.normal));
        if(distance<bestDistance){ bestDistance=distance; best=f; }
      }
      const s=foldCoord(best,p);
      return foldAboutRight ? [sharedCoord(p), s] : [s, sharedCoord(p)];
    }
  };
  console.log('hud facets: '+ctx.rawName+' faces='+faces.length+' fold='+maxFold.toFixed(1)+'deg about '+(foldAboutRight?'right':'up')
    +' canvas='+canvasWidthM.toFixed(3)+' x '+canvasHeightM.toFixed(3)+'m');
  return {
    // Only these keys reach the anchor JSON. `domain` is runtime-only (a function cannot be serialised)
    // and is deliberately returned OUTSIDE `fields` so the caller cannot leak it into the pack.
    fields:{canvasWidthM:canvasWidthM, canvasHeightM:canvasHeightM, faces:faces},
    domain:domain
  };
}

/**
 * The 2D DOMAIN of every anchor: the space a wiper's pivot and angles are measured in, in metres from
 * the anchor's own centre along its right/up axes (or, for a folded anchor, along the UNROLLED axes).
 * Filled while the anchors are built, consumed by the wipersweep fit.
 */
const DOMAINS=new Map();
/** Glass anchor name -> the fields fitted from its mmtr_wipersweep fan. */
const SWEEP_FITS=new Map();

/**
 * Fits the action sector of a wiper from its modelled TRIANGLE FAN.
 *
 * <p>A fan has exactly one vertex shared by every one of its triangles, and that vertex IS the pivot -
 * so "where does the wiper work" is answered by the geometry the modeller drew, not by numbers in a
 * config file. Everything is measured in the glass's 2D domain (see {@link DOMAINS}), which is the
 * same space the client simulates drops in, so a fan drawn on a curved glass fits too.</p>
 *
 * <p>The result uses the SAME fields the client already reads for a hand-configured wiper, so a flat
 * glass needs no client change at all: {@code pivotU}/{@code pivotV} (fractions from the left/bottom
 * edge), {@code armM}, {@code parkAngleDeg}/{@code sweepDeg} (degrees from the face's right edge,
 * positive towards up) and {@code sweepSign}.</p>
 *
 * <p>Every refusal names its reason on the console. A silently ignored sweep is a wiper that never
 * clears anything, which is close to undiagnosable from inside the game.</p>
 */
function fitWipersweep(group, domain){
  const label=group.name||'mmtr_wipersweep';
  if(!domain){ console.warn('WARNING: '+label+' cannot be fitted - its glass has no 2D domain.'); return null; }
  // Pivot = the vertex present in EVERY face: a fan's apex appears once per triangle, so it is the only
  // vertex whose count equals the face count.
  //
  // ...EXCEPT for a two-triangle fan, which IS a quad: both faces share an edge, so BOTH of that edge's
  // endpoints are "in every face" and the geometry alone cannot say which one is the pivot - the fact is
  // simply not in the mesh. For that case the authoring convention decides: write the apex as the FIRST
  // vertex of every face (which is also how a fan is normally wound by hand).
  const counts=new Map();
  for(const f of group.faces) for(const i of new Set(f)) counts.set(i,(counts.get(i)||0)+1);
  let apexes=[...counts.entries()].filter(entry=>entry[1]===group.faces.length).map(entry=>entry[0]);
  if(apexes.length>1){
    const firstVertices=new Set(group.faces.map(f=>f[0]));
    const conventional=firstVertices.size===1 ? [...firstVertices][0] : null;
    if(conventional!==null&&apexes.includes(conventional)){
      console.warn('NOTE: '+label+' is a '+group.faces.length+'-triangle fan, where the pivot is ambiguous '+
        '(the geometry is just a quad); using vertex '+conventional+' because every face starts with it.');
      apexes=[conventional];
    }
  }
  // The apex is now OPTIONAL. It is only needed for the legacy "angular sector" reading (a model with no
  // modelled blade), because the region's BOUNDARY is what the mechanism solve uses - and a boundary is a
  // property of the outline, not of a shared vertex. That matters because an IDEAL parallelogram moves its
  // blade purely by translation, so its two extreme positions are PARALLEL, their "apex" is at infinity,
  // and a triangle fan simply cannot express the region. A quad band can, and so can any polygon.
  const apex=apexes.length===1 ? apexes[0] : null;
  const allVertices=[...new Set(group.faces.flat())];
  const pivot=apex===null
    ? vAverage(allVertices.map(i=>domain.toRightUp(rc(i))))
    : domain.toRightUp(rc(apex));
  const rim=apex===null ? allVertices : allVertices.filter(i=>i!==apex);
  if(rim.length<2){ console.warn('WARNING: '+label+' has fewer than two rim vertices - ignored.'); return null; }

  let armM=0;
  const angles=[];
  for(const i of rim){
    const p=domain.toRightUp(rc(i));
    const dr=p[0]-pivot[0], dh=p[1]-pivot[1];
    armM=Math.max(armM,Math.hypot(dr,dh));
    angles.push(Math.atan2(dh,dr)*180/Math.PI);
  }
  // The sector is ONE contiguous arc, so it is the complement of the largest gap between the rim
  // angles: sort them, find the widest gap, and the sector runs from the angle after that gap round to
  // the angle before it. Doing it this way needs no "which end is the park position" convention and
  // survives angles that wrap through 180/-180.
  const sorted=[...angles].sort((a,b)=>a-b);
  let gapSize=-Infinity, gapIndex=0;
  for(let i=0;i<sorted.length;i++){
    const previous=i===0 ? sorted[sorted.length-1]-360 : sorted[i-1];
    const gap=sorted[i]-previous;
    if(gap>gapSize){ gapSize=gap; gapIndex=i; }
  }
  const parkAngleDeg=sorted[gapIndex];
  let sweepDeg=sorted[(gapIndex-1+sorted.length)%sorted.length]-parkAngleDeg;
  while(sweepDeg<0) sweepDeg+=360;
  // The guard is deliberately tiny. Under "the fan is the region the blade SWEEPS" a real train linkage
  // opens only a COUPLE OF DEGREES (the blade barely turns while the arm sweeps 50), so a small span is
  // the normal case, not a modelling mistake - only a truly collinear fan is degenerate.
  if(sweepDeg<0.2){
    console.warn('WARNING: '+label+' spans only '+sweepDeg.toFixed(2)+' degrees - its rim vertices are collinear, so it declares no area; ignored.');
    return null;
  }

  const pivotU=0.5+pivot[0]/domain.canvasWidthM;
  const pivotV=0.5+pivot[1]/domain.canvasHeightM;

  // The fan's BOUNDARY EDGES - the rim edges that are NOT incident to the apex, i.e. the lines that
  // bound the region it declares.
  //
  // A fan drawn as THE REGION THE BLADE SWEEPS (the meaning chosen for this project) has the blade's two
  // extreme positions among these edges, and those are what the mechanism solve needs: they are the
  // directions the blade has at the two ends of the stroke. A 4-rim fan gives THREE edges though - the
  // two blade positions AND the outer edge that joins them - so this returns all candidates and lets
  // fitWiperMechanism pick by testing which one the blade actually LIES ON. A plain angular sector (rim
  // points all at the same radius from the apex) has no such edges, and then the outline's own span is
  // the only boundary - the older meaning, kept so models that only draw a sweep sector keep working.
  // THE OUTLINE: an edge that belongs to exactly ONE face. A triangle fan's spokes belong to two faces,
  // so they drop out on their own - no apex needed, and the same rule accepts a quad band or any polygon.
  const edgeUse=new Map();
  const edgeKey=(a,b)=>a<b?a+'_'+b:b+'_'+a;
  for(const f of group.faces) for(let i=0;i<f.length;i++){ const k=edgeKey(f[i],f[(i+1)%f.length]); edgeUse.set(k,(edgeUse.get(k)||0)+1); }
  const boundaryEdges=[];
  for(const f of group.faces){
    for(let i=0;i<f.length;i++){
      const a=f[i], b=f[(i+1)%f.length];
      if(edgeUse.get(edgeKey(a,b))!==1) continue;
      const pa=domain.toRightUp(rc(a)), pb=domain.toRightUp(rc(b));
      if(Math.hypot(pb[0]-pa[0], pb[1]-pa[1])<=0.02) continue;
      const angle=Math.atan2(pb[1]-pa[1], pb[0]-pa[0])*180/Math.PI;
      if(!boundaryEdges.some(edge=>Math.abs(normaliseLineAngle(angle-edge.angle))<0.5)) {
        boundaryEdges.push({a:pa, b:pb, angle:angle});
      }
    }
  }
  const shape=boundaryEdges.length>=2 ? 'swept region' : 'angular sector';
  // A pivot far outside the glass is only a mistake for the OLD meaning (a sector about the pivot). For a
  // SWEPT-REGION fan the apex is a virtual centre and is EXPECTED to sit far away - that is what makes a
  // slight fan slight - so warning there would cry wolf on every correct model.
  if(shape==='angular sector'&&(pivotU<-0.5||pivotU>1.5||pivotV<-0.5||pivotV>1.5)){
    console.warn('WARNING: '+label+' puts its pivot at (u='+pivotU.toFixed(3)+', v='+pivotV.toFixed(3)+'), well outside its glass - '+
      'check that the fan is modelled on the glass it names.');
  }
  if(shape==='angular sector'){
    boundaryEdges.length=0;
    const from=polarDeg(pivot,armM,parkAngleDeg), to=polarDeg(pivot,armM,parkAngleDeg+sweepDeg);
    boundaryEdges.push({a:pivot, b:from, angle:parkAngleDeg}, {a:pivot, b:to, angle:parkAngleDeg+sweepDeg});
  }

  const fit={wiper:true, pivotU:+pivotU.toFixed(4), pivotV:+pivotV.toFixed(4), armM:+armM.toFixed(4),
    parkAngleDeg:+parkAngleDeg.toFixed(3), sweepDeg:+sweepDeg.toFixed(3), sweepSign:1,
    boundaryEdges:boundaryEdges};
  console.log('wiper sweep: '+label+' -> pivot u='+fit.pivotU+' v='+fit.pivotV+' arm='+fit.armM+
    'm park='+fit.parkAngleDeg+' sweep='+fit.sweepDeg+'deg ('+shape+', '+boundaryEdges.length+' boundary edge(s) at '+
    boundaryEdges.map(edge=>normaliseLineAngle(edge.angle).toFixed(2)).join(' / ')+')');
  return fit;
}

/** A point at {@code degrees} and {@code radius} from {@code from}, in the domain's (right, up). */
function polarDeg(from,radius,degrees){
  const radians=degrees*Math.PI/180;
  return [from[0]+radius*Math.cos(radians), from[1]+radius*Math.sin(radians)];
}

/** An angle folded into [0, 180): a LINE has no direction, so +/-180 is the same boundary. */
function normaliseLineAngle(degrees){
  let value=degrees%180;
  if(value<0) value+=180;
  return value;
}

function normaliseDegrees360(degrees){
  let value=degrees%360;
  if(value<0) value+=360;
  return value;
}

/**
 * The two ends of a bar-shaped part, as (right, up) in the glass's 2D domain.
 *
 * <p>Principal-axis fit rather than "the two furthest-apart vertices": for a box the furthest pair is a
 * DIAGONAL, which would put the blade's ends at opposite corners and make the blade longer than it is.
 * The principal axis of a long thin box is its long axis, and projecting onto it then taking the
 * extremes gives the two end faces.</p>
 */
function barEnds(faces, domain){
  const ids=[...new Set(faces.flat())];
  const points=ids.map(i=>domain.toRightUp(rc(i)));
  const centre=vAverage(points);
  let srr=0,sru=0,suu=0;
  for(const p of points){
    const dr=p[0]-centre[0], du=p[1]-centre[1];
    srr+=dr*dr; sru+=dr*du; suu+=du*du;
  }
  const theta=0.5*Math.atan2(2*sru, srr-suu);
  const axis=[Math.cos(theta),Math.sin(theta)];
  let min=Infinity,max=-Infinity;
  for(const p of points){
    const t=(p[0]-centre[0])*axis[0]+(p[1]-centre[1])*axis[1];
    if(t<min)min=t; if(t>max)max=t;
  }
  return [
    [centre[0]+axis[0]*min, centre[1]+axis[1]*min],
    [centre[0]+axis[0]*max, centre[1]+axis[1]*max]
  ];
}

/** Distance from a point to a segment, used to tell which end of the rod is bolted to the blade. */
function distanceToSegment(p, a, b){
  const dx=b[0]-a[0], dy=b[1]-a[1];
  const lengthSquared=dx*dx+dy*dy;
  const t=lengthSquared<1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0]-a[0])*dx+(p[1]-a[1])*dy)/lengthSquared));
  return Math.hypot(p[0]-(a[0]+dx*t), p[1]-(a[1]+dy*t));
}

/**
 * Fits the WIPER MECHANISM from the parts the modeller named independently, and answers the question
 * "does the blade turn, or does it translate?" - the difference between a car wiper and a train's
 * parallel linkage.
 *
 * <p>Both families are the same rigid segment whose two ends each rotate by the same angle about their
 * OWN pivot:</p>
 * <pre>
 *   A(theta) = P1 + R(theta)*(A0 - P1)
 *   B(theta) = P2 + R(theta)*(B0 - P2)
 * </pre>
 * <p>With {@code P1 = P2} (one spindle) that is a rigid rotation - the blade turns with the arm, which
 * is what a single-axis wiper does. With EQUAL link vectors ({@code A0 - P1 = B0 - P2}, i.e. a
 * parallelogram) the difference {@code B(theta) - A(theta)} is constant, so the blade keeps its
 * direction and only TRANSLATES. No branch is needed on the client: it evaluates the same two
 * expressions either way. See docs §1.4⑤.</p>
 *
 * @returns extra fields for the glass's windshield block, or null when the model has no blade part
 *   (in which case the client keeps drawing its own synthetic blade, exactly as before)
 */
function fitWiperMechanism(sweepFit, glass, domain){
  const pane=glass.pane||1;
  const blade=partFaces.find(p=>p.name.toLowerCase()==='wiper_'+glass.cab+'_'+pane&&p.faces.length);
  if(!blade) return null;
  if(!domain){ console.warn('WARNING: '+blade.name+' cannot be fitted - its glass has no 2D domain.'); return null; }

  const ends=barEnds(blade.faces, domain);
  const rod=partFaces.find(p=>p.name.toLowerCase()==='wiperrod_'+glass.cab+'_'+pane&&p.faces.length);
  const arm=partFaces.find(p=>p.name.toLowerCase()==='wiperarm_'+glass.cab+'_'+pane&&p.faces.length);

  // ---- the SPINDLE -------------------------------------------------------------------------------
  // The fan is the region the BLADE sweeps, so its apex is the region's VIRTUAL centre - the point where
  // the blade's two extreme LINES meet. That is NOT the spindle, and not only for a linkage: even a
  // single-axis blade is OFFSET from its pivot (the ARM passes through the spindle, the blade does not),
  // so its two extreme lines meet somewhere else entirely. The spindle therefore has to be stated, and
  // the only thing that can state it is the modelled ARM: its end that does NOT touch the blade.
  // (Distance-to-segment does not care which blade end is A and which is B, so this does not depend on
  // the assignment below.)
  let p1=[(sweepFit.pivotU-0.5)*domain.canvasWidthM, (sweepFit.pivotV-0.5)*domain.canvasHeightM];
  let armTip=null;
  if(arm){
    const armEnds=barEnds(arm.faces, domain);
    const near0=distanceToSegment(armEnds[0], ends[0], ends[1]);
    const near1=distanceToSegment(armEnds[1], ends[0], ends[1]);
    armTip=near0<=near1 ? armEnds[0] : armEnds[1];
    p1=near0<=near1 ? armEnds[1] : armEnds[0];
  } else if(rod){
    console.warn('WARNING: '+glass.name+' has a parallel linkage but no wiperarm_'+glass.cab+'_'+pane+
      ' - the spindle can only come from the arm (the fan apex is the SWEPT REGION\'s virtual centre, not the spindle). Falling back, so the stroke is probably wrong.');
  }

  // A = the blade end nearer the spindle, B = the other. Decided by GEOMETRY, or the two ends swap with
  // the winding and the mechanism turns inside out.
  const distanceToPivot0=Math.hypot(ends[0][0]-p1[0], ends[0][1]-p1[1]);
  const distanceToPivot1=Math.hypot(ends[1][0]-p1[0], ends[1][1]-p1[1]);
  const a0=distanceToPivot0<=distanceToPivot1 ? ends[0] : ends[1];
  const b0=distanceToPivot0<=distanceToPivot1 ? ends[1] : ends[0];

  let p2=p1;
  let rodPin=b0;
  let family='single-axis';
  let bladeTurnDeg=null;
  if(rod){
    const rodEnds=barEnds(rod.faces, domain);
    // The rod end bolted to the blade carrier is the one nearest the blade; the other is its pivot.
    const near0=distanceToSegment(rodEnds[0], a0, b0);
    const near1=distanceToSegment(rodEnds[1], a0, b0);
    p2=near0<=near1 ? rodEnds[1] : rodEnds[0];
    rodPin=near0<=near1 ? rodEnds[0] : rodEnds[1];
    family='parallel linkage';
    const attachmentGap=Math.min(near0, near1);
    if(attachmentGap>0.03){
      console.warn('WARNING: '+rod.name+' does not reach the blade ('+attachmentGap.toFixed(3)+' m away); check that the rod is modelled from its pivot to the blade carrier.');
    }
  }

  // ---- the STROKE, solved from the fan's far boundary ---------------------------------------------
  // The fan declares where the blade clears, i.e. its two extreme positions. One of those is the park
  // position the blade is modelled at; the other says which rotation of the LINKAGE puts the blade
  // there. So the arm's stroke is recovered by solving direction(phi) = the far boundary - which is why
  // the fan does NOT need to be the arm's own swing any more (a real train linkage turns its blade only
  // a couple of degrees over a 50 degree stroke, so the fan is a slight sliver, not a 50 degree sector).
  const parkDir=Math.atan2(b0[1]-a0[1], b0[0]-a0[0])*180/Math.PI;
  const edges=sweepFit.boundaryEdges||[];
  const directionAt=phi=>{
    const radians=phi*Math.PI/180, cos=Math.cos(radians), sin=Math.sin(radians);
    const dx=p2[0]-p1[0]+((b0[0]-p2[0])-(a0[0]-p1[0]))*cos-((b0[1]-p2[1])-(a0[1]-p1[1]))*sin;
    const dy=p2[1]-p1[1]+((b0[0]-p2[0])-(a0[0]-p1[0]))*sin+((b0[1]-p2[1])-(a0[1]-p1[1]))*cos;
    return Math.atan2(dy,dx)*180/Math.PI;
  };
  // THE PIN-BASED BLADE MOTION. The two points a linkage drives are the PINS, not the blade's ends: a real
  // arm is bolted to the blade's MIDDLE, so using an end scales the translation by |end-P1|/|pin-P1|.
  // (verify_windshield.js checkPinKinematics quantifies it: 11 mm over a 50 deg stroke on a 0.5 m arm.)
  // Both pins rotate by the same angle in a parallelogram; the blade is the rigid body through them, and
  // its ends follow from the rigid motion that takes the park pin pair onto the current one.
  const pinAPoint=armTip||a0;
  const pinBPoint=rodPin||b0;

  const bladeAt=phi=>{
    const radians=phi*Math.PI/180, cos=Math.cos(radians), sin=Math.sin(radians);
    const rotate=(pivot,point)=>{
      const dx=point[0]-pivot[0], dy=point[1]-pivot[1];
      return [pivot[0]+dx*cos-dy*sin, pivot[1]+dx*sin+dy*cos];
    };
    // NOT the pin-pair rigid motion. That was tried and MEASURED WRONG here (4.5 mm on this fixture): the
    // two pins move on two different circles, so "both rotate by the same phi" does not preserve the
    // distance between them - which the blade's rigidity requires - unless the link vectors are equal
    // (i.e. unless it is exactly a parallelogram). The pins are therefore only usable once the FOLLOWER'S
    // ANGLE IS SOLVED from the four-bar loop closure instead of assumed equal to the crank's. Until then
    // the blade's ends are rotated directly, which is at least self-consistent.
    return [rotate(p1,a0), rotate(p2,b0)];
  };

  let strokeDeg=sweepFit.sweepDeg;
  let strokeSign=sweepFit.sweepSign||1;
  let parkAngleDeg=sweepFit.parkAngleDeg;
  let solved=false;

  for(const edge of edges){
    // Do NOT exclude an edge just because its DIRECTION is close to park: in a real linkage the far blade
    // is only a couple of degrees away from the parked one - that is exactly what "slight fan" means - so
    // a direction filter would throw the real answer away. The park edge excludes itself below instead,
    // because solving for it returns phi = 0.
    // Solve for the stroke by POSITION, not by direction. Two traps make the direction alone useless:
    //   - a line angle repeats every 180 degrees, so a single-axis blade matches at phi, phi-180, ...;
    //   - a linkage's blade direction barely moves over the stroke, so a whole RANGE of phi matches the
    //     far edge within tolerance.
    // Where the blade actually LIES has neither problem: the blade's motion is a one-parameter family and
    // the edge is one specific member of it, so the residual has a unique zero at the real stroke. It is
    // also what tells a real blade position from the third edge of a 4-rim fan (the outer edge joining the
    // blade's two far ends), which no direction test can separate on a slight fan.
    let candidate=null, onEdge=Infinity;
    for(let phi=-180;phi<=180;phi+=0.05){
      const [aAt,bAt]=bladeAt(phi);
      const on=Math.max(distanceToSegment(aAt,edge.a,edge.b), distanceToSegment(bAt,edge.a,edge.b));
      if(on<onEdge){ onEdge=on; candidate=phi; }
    }

    // |phi| near zero means this edge IS the parked blade, not the far one.
    if(candidate===null||onEdge>0.005||Math.abs(candidate)<0.2) continue;
    strokeDeg=Math.abs(candidate);
    strokeSign=candidate<0?-1:1;
    parkAngleDeg=parkDir;
    solved=true;
    console.log('wiper mechanism: '+glass.name+' stroke solved as '+strokeDeg.toFixed(2)+' deg from the fan edge at '+
      normaliseLineAngle(edge.angle).toFixed(2)+' deg (blade on it to within '+(onEdge*1000).toFixed(1)+' mm); '+
      'the blade is within '+(onEdge*1000).toFixed(1)+' mm of that edge');
    break;
  }
  if(!solved){
    console.warn('WARNING: '+glass.name+' could not recover a stroke from '+mmtr_sweepLabel(glass)+
      '. The fan must be the region the BLADE SWEEPS: its rim has to include the blade at BOTH ends of the stroke (so its boundary edges are the two blade positions, not the arm\'s own swing).');
  }
  // The blade's OWN rotation over the stroke, now that the stroke is known: this is the fan's opening, and
  // the number that says whether this really is a parallel linkage (a couple of degrees) or a plain
  // single-axis wiper (it turns by the whole stroke).
  bladeTurnDeg=Math.abs(normaliseLineAngle(directionAt(strokeSign*strokeDeg)-directionAt(0)));

  // The arm, when modelled, is a cross-check: the end that is NOT the spindle should land ON the blade
  // segment. NOT on A0 - a real arm is bolted to the blade's MIDDLE, so a blade that extends past the
  // arm on one side (which is how they are built) is correct, and demanding it end at A0 flags every
  // real wiper.
  if(arm&&armTip){
    const bladeGap=distanceToSegment(armTip, a0, b0);
    if(bladeGap>0.03){
      console.warn('WARNING: '+arm.name+' reaches to '+bladeGap.toFixed(3)+' m off the blade; check the arm against '+
        mmtr_sweepLabel(glass)+'.');
    }
  }

  const toU=p=>0.5+p[0]/domain.canvasWidthM;
  const toV=p=>0.5+p[1]/domain.canvasHeightM;
  // pivotU/pivotV/armM are OVERWRITTEN with the SPINDLE and the blade's reach, because the fan's own
  // apex is the swept region's VIRTUAL centre (it can be metres outside the glass, and for pane 3 of the
  // fixture it is 7.6 m away). Everything that treats pivotU/pivotV as "the point a blade rotates about"
  // - the client's blade kinematics, and the legacy sector wipe's reach - means the SPINDLE.
  const reach=Math.max(Math.hypot(a0[0]-p1[0], a0[1]-p1[1]), Math.hypot(b0[0]-p1[0], b0[1]-p1[1]));
  const fields={
    pivotU:+toU(p1).toFixed(4), pivotV:+toV(p1).toFixed(4), armM:+reach.toFixed(4),
    bladeAU:+toU(a0).toFixed(4), bladeAV:+toV(a0).toFixed(4),
    bladeBU:+toU(b0).toFixed(4), bladeBV:+toV(b0).toFixed(4),
    // These OVERRIDE the fan's own readings, and that is the whole point of the solve above:
    // parkAngleDeg is the direction the modelled blade is parked at (the origin of the rotation), and
    // sweepDeg is the ARM's stroke recovered from the fan's far boundary - not the fan's own opening,
    // which is only the couple of degrees the blade turns.
    parkAngleDeg:+parkAngleDeg.toFixed(3), sweepDeg:+strokeDeg.toFixed(3), sweepSign:strokeSign
  };
  if(bladeTurnDeg!==null){
    // For a single-axis wiper "the blade turns by the whole stroke" IS the definition, so the hint about
    // remodelling only applies when there is a linkage to get wrong.
    const shape=family==='single-axis' ? 'single axis (the blade turns by the whole stroke)'
      : bladeTurnDeg<0.5 ? 'ideal parallelogram (blade only translates)'
      : bladeTurnDeg<20 ? 'slight fan (real train linkage)'
      : 'strong rotation for a linkage - consider a single-axis model instead';
    console.log('wiper mechanism: '+glass.name+' '+family+', blade turns '+bladeTurnDeg.toFixed(2)+
      ' deg over the '+strokeDeg.toFixed(2)+' deg stroke -> '+shape);
  } else {
    console.log('wiper mechanism: '+glass.name+' '+family+' (the blade rides the arm)');
  }
  // pivot2 is only written when there IS a second pivot, so a single-axis wiper's block stays small and
  // the client's default (P2 = P1) applies.
  if(rod){
    fields.pivot2U=+toU(p2).toFixed(4);
    fields.pivot2V=+toV(p2).toFixed(4);
  }
  // THE PINS - where each link is bolted to the BLADE. These, not the blade's own ends, are the two
  // points a linkage actually drives: a real arm is pinned to the blade's MIDDLE, so a model that uses
  // the blade's end instead translates the blade by the wrong amount (|end-P1| instead of |pin-P1|).
  // Emitted additively: the client ignores them until its kinematics is switched over to them, and the
  // blade's own park geometry (bladeAU/../bladeBV) stays in the file because the client needs BOTH -
  // the pins define the motion, the blade's ends define what to draw and wipe.
  if(armTip){
    fields.pinAU=+toU(armTip).toFixed(4);
    fields.pinAV=+toV(armTip).toFixed(4);
  }
  if(rod){
    fields.pinBU=+toU(rodPin).toFixed(4);
    fields.pinBV=+toV(rodPin).toFixed(4);
    console.log('wiper pins: '+glass.name+' arm pin '+Math.hypot(armTip?armTip[0]-p1[0]:0,armTip?armTip[1]-p1[1]:0).toFixed(3)+
      ' m from the spindle'+(armTip&&distanceToSegment(armTip,a0,b0)>0.01?' (NOT at a blade end - the pin-based kinematics is the correct one)':'')+
      ', rod pin '+Math.hypot(rodPin[0]-p2[0],rodPin[1]-p2[1]).toFixed(3)+' m from the second pivot');
  }
  return fields;
}

function mmtr_sweepLabel(glass){
  return 'mmtr_wipersweep_'+glass.cab+'_'+(glass.pane||1);
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
    // mmtr_windshield[_<cab>][_<pane>] - the rain/wiper glass, and mmtr_wipersweep_<cab>_<pane> - the
    // sector that glass's wiper sweeps (see docs §1.4).
    //
    // INDEX RULE - one index is the CAB, two are cab + pane:
    //   mmtr_windshield_1     = cab 1, pane 1     (every existing model means this)
    //   mmtr_windshield_2     = CAB 2, pane 1     <- NOT "cab 1 pane 2"
    //   mmtr_windshield_1_2   = cab 1, pane 2
    // Reading a lone index as a pane instead would silently turn SAF101v2's two screens (one per cab)
    // into "two panes of cab 1" - no error, wrong glass. The pane defaults to 1 either way.
    const wsm=/^windshield(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    const swm=/^wipersweep(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    const kind=wsm?'windshield':(swm?'wipersweep':(m?m[1]:rawName));
    const cab=wsm?(wsm[1]?+wsm[1]:1):(swm?(swm[1]?+swm[1]:1):(m&&m[2]?+m[2]:null));
    const pane=(wsm||swm)?((wsm?wsm[2]:swm[2])?+(wsm?wsm[2]:swm[2]):1):null;
    const door=(wsm||swm)?null:(m&&m[3]?+m[3]:null);
    const anchor={name:rawName,kind:kind,cab:cab,door:door,car:params.carIndex||0,
      x:+c[0].toFixed(5), y:+c[1].toFixed(5), z:+c[2].toFixed(5),
      normal:n.map(x=>+x.toFixed(6)), up:up.map(x=>+x.toFixed(6)), right:right.map(x=>+x.toFixed(6)),
      widthM:+(wMax-wMin).toFixed(4), heightM:+(hMax-hMin).toFixed(4)};
    // The pane number is written ONLY when it is not 1, so a single-pane glass (which is what every
    // model built before multi-pane existed has) keeps a byte-identical anchor entry. Absent = pane 1.
    if(pane!==null && pane!==1) anchor.pane=pane;
    // A folded dashboard/glass adds canvasWidthM/canvasHeightM + one entry per face. The fields above
    // keep their old meaning, so a client that does not know about facets still draws the old quad.
    const facetResult=buildFacets(g,{kind:kind,refFace:f0,refNormal:n,refUp:up,refRight:right,groupCentroid:c,flip:flipThis,rawName:rawName});
    if(facetResult) for(const key of Object.keys(facetResult.fields)) anchor[key]=facetResult.fields[key];
    // The 2D domain every wiper pivot/angle is measured in. A flat anchor measures straight off its
    // own frame; a folded one uses the unrolled frame from buildFacets.
    DOMAINS.set(rawName, facetResult ? facetResult.domain : {
      canvasWidthM:anchor.widthM, canvasHeightM:anchor.heightM,
      toRightUp: p => { const d=sub(p,c); return [dot(d,right), dot(d,up)]; }
    });
    anchors.push(anchor);
  }

  // ---- second pass: the wiper ACTION SECTOR of each glass -----------------------------------------
  // A mmtr_wipersweep group is a TRIANGLE FAN, and a fan has exactly one vertex shared by ALL of its
  // triangles - that shared vertex IS the wiper's pivot. Fitting it here means the modeller DRAWS
  // where the wiper works instead of typing pivot/angles into a config file, and because the fit lands
  // in the very fields the client already reads (pivotU/pivotV/armM/parkAngleDeg/sweepDeg/sweepSign),
  // a flat glass needs no client code at all for it.
  const byName=new Map(anchors.map(a=>[a.name,a]));
  for(const g of anchorFaces){
    const name=(g.name||'').replace(/^mmtr_/,'');
    const sweep=byName.get(name);
    if(!sweep||sweep.kind!=='wipersweep') continue;
    // `pane` is only written to the JSON when it is not 1, so read it defensively here: the pair key is
    // always spelled out in full (windshield_<cab>_<pane>).
    const pane=sweep.pane||1;
    const glass=byName.get('windshield_'+sweep.cab+'_'+pane);
    if(!glass){
      console.warn('WARNING: '+g.name+' has no matching mmtr_windshield_'+sweep.cab+'_'+pane+
        ' - there is nothing for it to sweep, so it is ignored (check the cab/pane numbers).');
      continue;
    }
    const fit=fitWipersweep(g, DOMAINS.get(glass.name));
    if(fit){
      // The modelled parts say whether the blade RIDES the arm (one pivot) or is carried by a
      // parallelogram linkage (two pivots). This is what makes a train's pantograph wiper work with the
      // same client code as a car's - see fitWiperMechanism.
      const mechanism=fitWiperMechanism(fit, glass, DOMAINS.get(glass.name));
      if(mechanism) for(const key of Object.keys(mechanism)) fit[key]=mechanism[key];
      SWEEP_FITS.set(glass.name, fit);
    }
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
const WINDSHIELD_FIELDS=['raindrops','fallMps','maxStreakM','wiper','drawBlade','dualWiper','armM','parkAngleDeg',
                         'sweepDeg','sweepSign','periodS','pivotU','pivotV','bladeWidthM','colour','armColour','snow','twoSided',
                         'pivot2U','pivot2V','bladeAU','bladeAV','bladeBU','bladeBV','pinAU','pinAV','pinBU','pinBV',
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
          const mn=/^mmtr_(windshield(?:_\d+)?(?:_\d+)?)$/.exec(String(entry.anchor));
          if(mn) key=mn[1];
        }
        // Legacy "index" meant the cab; "cab"/"pane" is the explicit form (pane defaults to 1).
        if(!key&&entry&&(entry.cab!==undefined||entry.pane!==undefined)) key='windshield_'+(entry.cab||1)+'_'+(entry.pane||1);
        if(!key&&entry&&entry.index!==undefined) key='windshield_'+entry.index;
        if(!key){ console.warn('windshield array entry has no usable anchor/cab+pane/index, ignored'); continue; }
        put(key,entry);
      }
    } else {
      // Keyed by anchor name, with or without the "mmtr_" prefix.
      for(const rawKey of Object.keys(ws)){
        const mn=/^(?:mmtr_)?(windshield(?:_\d+)?(?:_\d+)?)$/.exec(String(rawKey));
        if(!mn){ console.warn('windshield key "'+rawKey+'" is not a windshield anchor name, ignored'); continue; }
        put(mn[1],ws[rawKey]);
      }
    }
  }
  // Models that have windshields but no authored block still get an entry, so the file shows where to tune.
  for(const anchor of anchors){
    if(anchor.kind!=='windshield'||byAnchor[anchor.name]) continue;
    byAnchor[anchor.name]={};
  }
  // Merge the fitted wiper sectors, and decide whether the client should draw its own blade.
  for(const anchor of anchors){
    if(anchor.kind!=='windshield') continue;
    const values=byAnchor[anchor.name]||(byAnchor[anchor.name]={});
    const fit=SWEEP_FITS.get(anchor.name);
    if(fit){
      // The modelled sector fills in whatever the config did not state outright, so an explicit
      // parkAngleDeg in the pack config still wins (that is the escape hatch for tuning).
      // Only real config fields are copied: the fit also carries internal values (the fan's boundary
      // lines) that have no business in the pack.
      for(const field of Object.keys(fit)) if(WINDSHIELD_FIELDS.includes(field)&&values[field]===undefined) values[field]=fit[field];
    }
    // A modelled SOLID wiper replaces the drawn blade. It must NOT switch the wiper off: the glass
    // still has to be wiped, and the modelled arm is what the animation is meant to move (that part is
    // still to come - see docs §1.4④). Only the mod's own blade geometry is suppressed.
    // This is the ONLY case in which a client default is overridden, which is what keeps every model
    // packed before solid wipers existed drawing exactly what it drew before.
    if(wiperGroups.includes('wiper_'+anchor.cab+'_'+(anchor.pane||1))&&values.drawBlade===undefined) values.drawBlade=false;
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
// The solid wiper(s): one EXTERIOR part each, at their modelled (parked) position. They do not move
// yet - rotating a part about its pivot is not something MTR's part renderer can do today (see
// docs §1.4④); until then the client's own drawn blade is the one that sweeps.
for(const g of wiperGroups){
  parts.push({names:[g],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:params.doorAnimationType||'STANDARD'});
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
