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
  // NOTE: keep this list in step with RESERVED_ANCHOR_KINDS below and with the <kind> alternation in
  // the structured-name regex further down. It exists to WARN about a role pattern swallowing an anchor
  // kind; a kind missing here gets no warning (the anchor just silently becomes a visible part).
  const ANCHOR_KINDS=['hud','seat','cabdoor','ack','windshield','wipersweep','light','door','pid','next','face'];
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
// Wildcards in groupRename: a key containing "*" matches any run of characters, and the "*" in the
// VALUE is replaced with whatever the key's "*" matched.
//
// WHY (2026-09-28, the headlights): the B-end control car is the SAME obj baked with rotationDegY 180,
// so every indexed anchor group has to be renumbered 1 -> 2, and the lamps are named
// mmtr_light_<cab>_<n> - one per lamp, a count that grows whenever someone models another lamp. An
// explicit table silently misses the next one: that car then carries an anchor whose cab number
// contradicts every other anchor on it, and nothing fails. One rule covers any count:
//   "mmtr_light_1_*": "mmtr_light_2_*"
const wildcardRenames=Object.keys(groupRename)
  .filter(key=>key.indexOf('*')>=0)
  .map(key=>{ const i=key.indexOf('*'); return {head:key.slice(0,i), tail:key.slice(i+1), to:String(groupRename[key])}; })
  .sort((a,b)=>(b.head.length+b.tail.length)-(a.head.length+a.tail.length));   // more specific wins
function renameGroup(name){
  const exact=groupRename[name];
  if(exact!==undefined) return exact;
  for(const rule of wildcardRenames){
    if(name.length<rule.head.length+rule.tail.length||!name.startsWith(rule.head)||!name.endsWith(rule.tail)) continue;
    return rule.to.replace('*', name.slice(rule.head.length, name.length-rule.tail.length));
  }
  return name;
}
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
const RESERVED_ANCHOR_KINDS=['hud','seat','cabdoor','ack','windshield','wipersweep','light','pid','next','face'];
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
/**
 * The name of ONE solid wiper part: {@code <prefix>_<cab>_<pane>[_<wiper>]}.
 *
 * <p>The optional THIRD index is which wiper of that glass it is, and it is omitted for the first one -
 * so every part named before multi-wiper existed keeps its exact name ({@code wiper_1_1},
 * {@code wiperarm_1_1}, ...) and nothing already in a pack moves. A glass that carries TWO wipers (a
 * wide screen with a pair on the scuttle, or a bus-style pantograph pair) names them
 * {@code wiper_1_1} and {@code wiper_1_1_2}, each with its own {@code mmtr_wipersweep_1_1} /
 * {@code mmtr_wipersweep_1_1_2} fan and its own pivot and park direction. See docs §1.4②/④.</p>
 */
function wiperPartName(prefix,cab,pane,wiper){ return prefix+'_'+cab+'_'+(pane||1)+((wiper||1)>1?'_'+wiper:''); }
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
    else if(/^wiper(arm|rod)?_\d+_\d+(?:_\d+)?$/i.test(raw2)){
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
// MERGE SAME-NAMED GROUPS - without this a vehicle can be present and effectively INVISIBLE.
//
// MTR keys its parts by GROUP NAME (nameToObjModels.put(name, model)), so two groups with the same name
// overwrite each other and only the LAST one is drawn. groupMap deliberately maps SEVERAL source groups
// onto one role - the BR101's body covers car_body + buffer_beam + buffers + bogies + wiper_motors - so
// renaming alone left FIVE "g body" blocks in the pack and the train rendered as just the last of them
// (the wiper motors): the whole shell was there in the file and nothing showed in game. The same trap
// catches any model whose config maps more than one object to a role.
//
// Vertex lines are hoisted into one prelude so the absolute face indices stay valid; then each name is
// emitted exactly once, with its faces in their original order and each face's `usemtl` replayed.
function mergeSameNamedGroups(lines){
  const prelude=[], order=[], faces=new Map(), faceMaterial=new Map();
  let group=null, material=null;
  for(const line of lines){
    const t=line.trim();
    if(t.startsWith('g ')||t.startsWith('o ')){
      const name=t.slice(2).trim();
      if(!faces.has(name)){ faces.set(name,[]); faceMaterial.set(name,[]); order.push(name); }
      group=name;
    } else if(t.startsWith('f ')&&group!==null){
      faces.get(group).push(line);
      faceMaterial.get(group).push(material);
    } else if(t.startsWith('usemtl ')){
      material=t.slice(7).trim();          // replayed per face below, so it stays with its geometry
    } else {
      prelude.push(line);
    }
  }
  const merged=prelude.slice();
  for(const name of order){
    const list=faces.get(name);
    if(!list.length) continue;
    merged.push('g '+name);
    const materials=faceMaterial.get(name);
    let last=null;
    for(let i=0;i<list.length;i++){
      if(materials[i]!==last){ if(materials[i]) merged.push('usemtl '+materials[i]); last=materials[i]; }
      merged.push(list[i]);
    }
  }
  const sourceGroups=lines.filter(l=>/^(g|o) /.test(l.trim())).length;
  if(sourceGroups>order.length) console.log('merged groups: '+sourceGroups+' source group(s) -> '+order.length+' part name(s)');
  return merged;
}
const objMain=mergeSameNamedGroups(out).join('\n');

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
/**
 * SWEEP ANCHOR name -> the fields fitted from that fan. Keyed by the SWEEP's own name and not by the
 * glass, because one glass may carry several wipers: {@code wipersweep_1_1} and {@code wipersweep_1_1_2}
 * are two fans over the same {@code windshield_1_1}, and keying by glass would silently keep only the
 * last one (the other wiper would then sweep the first one's sector).
 */
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
  // Two boundary edges are the SAME boundary only if they lie on the same LINE - being merely PARALLEL is
  // not enough. Deduplicating by ANGLE alone collapses a BAND, whose two extreme blade positions are
  // parallel and distinct, down to a single edge; with only one edge left the fit gives up on the outline
  // and falls back to reading the region as an angular sector, which reports a 185-degree stroke for a
  // 35-degree band. Compare against the line, not against the direction.
  const onSameLine=(edge,pa,pb)=>{
    const dx=pb[0]-pa[0], dy=pb[1]-pa[1];
    const length=Math.hypot(dx,dy);
    if(length<=1.0E-9) return true;
    const nx=-dy/length, ny=dx/length;
    return Math.abs(nx*(edge.a[0]-pa[0])+ny*(edge.a[1]-pa[1]))<=0.002 &&
      Math.abs(nx*(edge.b[0]-pa[0])+ny*(edge.b[1]-pa[1]))<=0.002;
  };
  const boundaryEdges=[];
  for(const f of group.faces){
    for(let i=0;i<f.length;i++){
      const a=f[i], b=f[(i+1)%f.length];
      if(edgeUse.get(edgeKey(a,b))!==1) continue;
      const pa=domain.toRightUp(rc(a)), pb=domain.toRightUp(rc(b));
      if(Math.hypot(pb[0]-pa[0], pb[1]-pa[1])<=0.02) continue;
      const angle=Math.atan2(pb[1]-pa[1], pb[0]-pa[0])*180/Math.PI;
      if(!boundaryEdges.some(edge=>onSameLine(edge,pa,pb))) {
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

/**
 * The rotation taking one LINE direction to another, folded into (-90, 90].
 *
 * NOT normaliseLineAngle, and the distinction is a real defect rather than pedantry: folding a
 * DIFFERENCE into [0, 180) turns a small turn in the negative sense into ~180. Measured on the
 * middle-pin fixture, the blade turns -1.27 deg and was reported as "turns 178.73 deg", which then
 * tripped the "strong rotation for a linkage - consider a single-axis model instead" warning on a
 * perfectly correct linkage. A direction folds into [0,180); a rotation amount folds into (-90, 90].
 */
function lineDeltaDeg(fromDeg,toDeg){
  let value=(toDeg-fromDeg)%180;
  if(value>90) value-=180;
  while(value<=-90) value+=180;
  return value;
}

function normaliseDegrees360(degrees){
  let value=degrees%360;  if(value<0) value+=360;
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
/**
 * Where a circle of radius {@code radiusA} about {@code a} meets one of radius {@code radiusB} about
 * {@code pivot} - the two assembly solutions of a two-link chain, or null when they do not meet.
 *
 * <p>The first radius belongs to the circle about the FIRST point. Getting that pair the wrong way round
 * silently returns the two intersections of the wrong circles, which look plausible and are metres out.</p>
 */
function circleCircle(a,pivot,radiusA,radiusB){
  const dx=pivot[0]-a[0], dy=pivot[1]-a[1];
  const distance=Math.hypot(dx,dy);
  if(distance<1.0E-9||distance>radiusA+radiusB||distance<Math.abs(radiusA-radiusB)) return null;
  const along=(distance*distance+radiusA*radiusA-radiusB*radiusB)/(2*distance);
  const height=Math.sqrt(Math.max(0,radiusA*radiusA-along*along));
  const ux=dx/distance, uy=dy/distance;
  const base=[a[0]+ux*along, a[1]+uy*along];
  return [
    [base[0]-uy*height, base[1]+ux*height],
    [base[0]+uy*height, base[1]-ux*height]
  ];
}

function fitWiperMechanism(sweepFit, glass, domain, wiper){
  const pane=glass.pane||1;
  const w=wiper||1;
  const blade=partFaces.find(p=>p.name.toLowerCase()===wiperPartName('wiper',glass.cab,pane,w)&&p.faces.length);
  if(!blade) return null;
  if(!domain){ console.warn('WARNING: '+blade.name+' cannot be fitted - its glass has no 2D domain.'); return null; }

  const ends=barEnds(blade.faces, domain);
  // How far a point is from the blade's GEOMETRY (not from its centre line).
  //
  // A real blade is not a bare bar: it carries a CARRIER PLATE - the triangle the rods bolt to - and that
  // plate is part of the same rigid `wiper_` mesh. Measuring a pin against the centre line therefore reports
  // a CORRECT wiper as broken: on the real BR101 the arm's pin is 63 mm from the line and the rod's 124 mm,
  // while BOTH are 28 mm from the blade's own geometry.
  const bladePoints=[...new Set(blade.faces.flat())].map(i=>domain.toRightUp(rc(i)));
  const offBladeBody=point=>{
    let min=distanceToSegment(point, ends[0], ends[1]);
    for(const p of bladePoints){
      const d=Math.hypot(p[0]-point[0], p[1]-point[1]);
      if(d<min) min=d;
    }
    return min;
  };
  const rod=partFaces.find(p=>p.name.toLowerCase()===wiperPartName('wiperrod',glass.cab,pane,w)&&p.faces.length);
  const arm=partFaces.find(p=>p.name.toLowerCase()===wiperPartName('wiperarm',glass.cab,pane,w)&&p.faces.length);

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
    console.warn('WARNING: '+glass.name+' has a parallel linkage but no '+wiperPartName('wiperarm',glass.cab,pane,w)+
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
    const attachmentGap=Math.min(offBladeBody(rodEnds[0]), offBladeBody(rodEnds[1]));
    if(attachmentGap>0.05){
      console.warn('WARNING: '+rod.name+' is '+attachmentGap.toFixed(3)+' m off the blade geometry; check that the rod is modelled from its pivot to the blade carrier.');
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

  // ---- THE FOUR-BAR LOOP CLOSURE ----------------------------------------------------------------
  // The blade is RIGID, so the distance between its two PINS cannot change. That single fact is the
  // constraint the follower's angle has to satisfy, and it makes that angle unique:
  //
  //   M(phi)  = P1 + R(phi)(M0 - P1)                              the crank (driven, rigid)
  //   Br(phi) = circle(P2, |Br0-P2|) n circle(M(phi), |Br0-M0|)    the follower (SOLVED, not assumed)
  //
  // "Both pins turn by phi" - what an ideal parallelogram does - violates the rigidity as soon as the two
  // link vectors differ, and the contradiction was measured at 4.5 mm on this fixture. The pins are only
  // usable once this is solved, which is why the loop closure comes FIRST.
  const pinSpan=Math.hypot(pinBPoint[0]-pinAPoint[0], pinBPoint[1]-pinAPoint[1]);
  const followerM=Math.hypot(pinBPoint[0]-p2[0], pinBPoint[1]-p2[1]);
  let assemblyMode=0;
  if(rod){
    // Which of the two intersections is the parked one: the ASSEMBLY MODE, constant while the mechanism
    // moves, so it is read off the PARK configuration rather than decided per frame.
    //
    // It is read off by HANDEDNESS - the sign of the parked triangle's area - and NOT by "which candidate
    // is nearer the parked pin". The two candidates are mirror images across the P2->M0 line, so their
    // handedness signs always differ while their distances can be nearly equal: near a toggle position the
    // circles are almost tangent and "nearer" flips on a sub-micron difference. The pins here are recovered
    // from mesh geometry (PCA), not read from constants, so a hair of error is the normal case - and a
    // proximity test then picks the MIRROR branch, which moves the blade by centimetres. The handedness of
    // the parked configuration cannot flip that way, so the generator, this packager and the client all
    // resolve the same branch from the same geometry.
    const handedness=(a,b,c)=>(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
    const probe=circleCircle(pinAPoint, p2, pinSpan, followerM);
    if(probe){
      const parkSign=handedness(p2,pinAPoint,pinBPoint)>=0 ? 1 : -1;
      assemblyMode=(handedness(p2,pinAPoint,probe[0])>=0 ? 1 : -1)===parkSign ? 0 : 1;
    } else {
      console.warn('WARNING: '+glass.name+' the two links cannot be assembled at park (|Br0-M0| = '+pinSpan.toFixed(3)+
        ' m is unreachable for the follower) - check the arm/rod form a closed linkage.');
    }
  }
  const rotateLocal=(pivot,point,cos,sin)=>{
    const dx=point[0]-pivot[0], dy=point[1]-pivot[1];
    return [pivot[0]+dx*cos-dy*sin, pivot[1]+dx*sin+dy*cos];
  };
  /** The two pins at a crank angle, or null when the linkage cannot be assembled there. */
  const pinPairAt=phi=>{
    const radians=phi*Math.PI/180, cos=Math.cos(radians), sin=Math.sin(radians);
    const m=rotateLocal(p1,pinAPoint,cos,sin);
    if(!rod) return [m, m];
    const solutions=circleCircle(m, p2, pinSpan, followerM);
    return solutions ? [m, solutions[assemblyMode]] : null;
  };

  const bladeAt=phi=>{
    const radians=phi*Math.PI/180, cos=Math.cos(radians), sin=Math.sin(radians);
    const rotate=(pivot,point)=>{
      const dx=point[0]-pivot[0], dy=point[1]-pivot[1];
      return [pivot[0]+dx*cos-dy*sin, pivot[1]+dx*sin+dy*cos];
    };
    // No linkage: the blade rides the arm, so a rigid rotation about the spindle is exact.
    if(!rod) return [rotate(p1,a0), rotate(p2,b0)];
    const pair=pinPairAt(phi);
    if(pair===null) return [rotate(p1,a0), rotate(p2,b0)];
    const m=pair[0], br=pair[1];
    // T: rotate the PARK pin pair onto the current one. The two distances now agree BY CONSTRUCTION -
    // that is exactly what the loop closure enforces - so this rigid motion is well defined. (With the
    // follower angle assumed equal to the crank's they did NOT agree, and T was ill-defined.)
    const turn=Math.atan2(br[1]-m[1],br[0]-m[0])-Math.atan2(pinBPoint[1]-pinAPoint[1],pinBPoint[0]-pinAPoint[0]);
    const tc=Math.cos(turn), ts=Math.sin(turn);
    const apply=point=>{
      const dx=point[0]-pinAPoint[0], dy=point[1]-pinAPoint[1];
      return [m[0]+dx*tc-dy*ts, m[1]+dx*ts+dy*tc];
    };
    return [apply(a0), apply(b0)];
  };

  let strokeDeg=sweepFit.sweepDeg;
  let strokeSign=sweepFit.sweepSign||1;
  let parkAngleDeg=sweepFit.parkAngleDeg;
  let solved=false;

  // Pick the GLOBAL best match, not the first edge that happens to match.
  //
  // A swept band's rim is its arc tessellated into ~100 chords, so MANY edges match within the residual bar,
  // and a chord's best phi can sit on the OTHER side of park from the real cap. Breaking on the first edge
  // that matched therefore wrote the opposite sweepSign on 3 of the 4 panes of the real BR101 - the wiper
  // would have swept the wrong way in game. The verifier has always taken the global minimum; the packager
  // must agree with it, and with the client, which rotates by (angle-park)*sweepSign.
  let best=null;
  const matches=[];
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
    if(candidate===null||onEdge>0.03) continue;
    matches.push({onEdge, candidate, edge});
  }
  /*
   * THE PARK CAP IS EXCLUDED BY IDENTITY, NOT BY A phi THRESHOLD.
   *
   * The parked blade LIES ON the park cap, so a tiny rotation of it still lands within a few millimetres
   * of that same edge: measured on the SAF420 control car (notes/279 follow-up) the 30 mm bar admitted
   * phi = 0.25 deg with a 4.7 mm residual, which beat the real far cap's 26 mm at phi = 90.00 deg. The
   * packager happened to survive that fan (it wrote 88.80 deg, 1.2 deg short of the modelled 90.000), but
   * the same leak is what once wrote "298.96 deg / 0.30 deg" into the BR101 pack, and the independent
   * verifier DID fall for it on this model. So: find the match nearest phi = 0 - that is the park cap -
   * and drop it before choosing. What survives is a genuinely different blade position.
   */
  if(matches.length){
    const parkCap=matches.reduce((a,b)=>Math.abs(a.candidate)<=Math.abs(b.candidate)?a:b);
    const pole=Math.abs(parkCap.candidate);
    const usable=matches.filter(m=>m!==parkCap&&Math.abs(m.candidate)>Math.max(2, pole+1));
    if(usable.length===0){
      console.warn('WARNING: '+glass.name+' has no fan edge but the parked blade\'s own - the fan must '+
        'include the blade at BOTH ends of the stroke, so its far cap is missing (or the blade does not '+
        'move). Falling back to the region read as an angular sector.');
    } else {
      best=usable.reduce((a,b)=>a.onEdge<=b.onEdge?a:b);
    }
    if(best){
      console.log('wiper mechanism: '+glass.name+' park cap = the edge at phi='+parkCap.candidate.toFixed(2)+
        ' deg ('+(parkCap.onEdge*1000).toFixed(1)+' mm from the parked blade), dropped from the solve');
    }
  }
  if(best!==null){
    strokeDeg=Math.abs(best.candidate);
    strokeSign=best.candidate<0?-1:1;
    parkAngleDeg=parkDir;
    solved=true;
    console.log('wiper mechanism: '+glass.name+' stroke solved as '+strokeDeg.toFixed(2)+' deg from the fan edge at '+
      normaliseLineAngle(best.edge.angle).toFixed(2)+' deg (blade on it to within '+(best.onEdge*1000).toFixed(1)+' mm); '+
      'the blade is within '+(best.onEdge*1000).toFixed(1)+' mm of that edge');
  }
  if(!solved){
    console.warn('WARNING: '+glass.name+' could not recover a stroke from '+mmtr_sweepLabel(glass,w)+
      '. The fan must be the region the BLADE SWEEPS: its rim has to include the blade at BOTH ends of the stroke (so its boundary edges are the two blade positions, not the arm\'s own swing).');
  }
  // The blade's OWN rotation over the stroke, now that the stroke is known: this is the fan's opening, and
  // the number that says whether this really is a parallel linkage (a couple of degrees) or a plain
  // single-axis wiper (it turns by the whole stroke).
  // The blade's OWN rotation over the stroke, measured on the REAL mechanism (bladeAt, i.e. through the
  // loop closure) rather than on the old "both ends rotate together" shortcut. The two differ - 3.95 deg
  // against 2.13 deg on the swept-region fixture - and the real one is what the linkage actually does.
  {
    const directionOf=pair=>Math.atan2(pair[1][1]-pair[0][1],pair[1][0]-pair[0][0])*180/Math.PI;
    bladeTurnDeg=Math.abs(lineDeltaDeg(directionOf(bladeAt(0)), directionOf(bladeAt(strokeSign*strokeDeg))));
  }

  // THE SUCCESS CRITERION for the loop closure: the pin span must not change, at ANY angle. The blade is
  // rigid, so a linkage that fails this is not describing the mechanism the modeller built - and if it
  // fails, everything downstream (the stroke, the wiped band, the part rotation) is built on sand.
  if(rod){
    let worstSpan=0, unreachable=false, failedAt=0;
    // THE STROKE, not a full revolution. A limited-stroke wiper linkage physically cannot be cranked all
    // the way round - past its stroke the crank points at the second spindle and the two circles stop
    // meeting - so demanding every angle fails a correct mechanism (it did: the first middle-pin fixture
    // is assemblable over its 35 deg stroke and over nothing else). The criterion is conservation over
    // the whole STROKE, which is what is measured here.
    for(let step=0;step<=40;step++){
      const phi=strokeSign*strokeDeg*step/40;
      const pair=pinPairAt(phi);
      if(pair===null){ unreachable=true; failedAt=phi; break; }
      worstSpan=Math.max(worstSpan,Math.abs(Math.hypot(pair[1][0]-pair[0][0],pair[1][1]-pair[0][1])-pinSpan));
    }
    if(unreachable){
      console.warn('WARNING: '+glass.name+' the linkage cannot be assembled at '+failedAt.toFixed(2)+
        ' deg, i.e. INSIDE its '+strokeDeg.toFixed(2)+' deg stroke - check the arm and rod lengths against the pivot spacing.');
    } else {
      console.log('wiper linkage: '+glass.name+' pin span '+pinSpan.toFixed(4)+' m conserved to '+(worstSpan*1e6).toFixed(3)+
        ' um over the whole '+strokeDeg.toFixed(2)+' deg stroke'+(worstSpan<1.0E-9?'':' *** THE BLADE IS RIGID, SO THIS MUST BE ZERO ***'));
    }
    // Extra information, explicitly NOT a defect: how far the linkage can be cranked before it comes apart.
    let fullRevolution=true;
    for(let phi=-180;phi<=180;phi+=2.5){ if(pinPairAt(phi)===null){ fullRevolution=false; break; } }
    if(!fullRevolution){
      console.log('wiper linkage: '+glass.name+' (assembled over its stroke only, not over a full revolution - normal for a limited-stroke wiper)');
    }
  }

  // The arm, when modelled, is a cross-check: the end that is NOT the spindle should land ON the blade
  // segment. NOT on A0 - a real arm is bolted to the blade's MIDDLE, so a blade that extends past the
  // arm on one side (which is how they are built) is correct, and demanding it end at A0 flags every
  // real wiper.
  if(arm&&armTip){
    const bladeGap=offBladeBody(armTip);
    if(bladeGap>0.05){
      console.warn('WARNING: '+arm.name+' is '+bladeGap.toFixed(3)+' m off the blade geometry; check the arm against '+
        mmtr_sweepLabel(glass,w)+'.');
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

function mmtr_sweepLabel(glass, wiper){
  return wiperPartName('mmtr_wipersweep',glass.cab,glass.pane||1,wiper||1);
}

/**
 * THE LAST TRANSFORM, AND THE ONE MTR ACTUALLY DEPENDS ON: un-weld + triangulate into the index-locked
 * layout ObjModelLoader reads (notes/194).
 *
 * MTR welds the i-th vertex to the i-th uv and the i-th normal and then reads only the first three
 * corners of a face. It never looks at the `v/vt/vn` triple a face writes. So a stock Blender export
 * (shared vertices, separate vt/vn index spaces, quads) is read as: every vertex past #vt samples uv
 * (0,0) - the WRONG texel, so the part turns invisible or samples another part of the atlas - and every
 * quad silently loses its fourth corner. Neither is an error message; the symptom is "part of the model
 * does not appear".
 *
 * What it does:
 *   · every face corner (v, vt, vn) becomes ONE output index, so the three pools stay the same length and
 *     `f i/i/i` is true by construction;
 *   · identical triples are de-duplicated, so the pools stay as small as the model really is;
 *   · polygons are fanned into triangles (v0,vi,vi+1), which is exactly the area-preserving triangulation
 *     the L3 check in verify_mtr_obj.js measures;
 *   · the POOL TEXT IS COPIED VERBATIM - only the numbering changes. That is why a packed file can carry
 *     `vn 1.000000 0.000000 0.000000` and `vn 0 0 1` side by side: the numbers are whatever the assembly
 *     above wrote, and re-formatting them here would be a second, silent edit of the geometry.
 *
 * Layout: any leading non-geometry line (mtllib), then all v, then all vt, then all vn, then the groups,
 * materials and faces in their original order.
 */
function toMtrObj(text){
  const lines=text.split('\n');
  const poolText={v:[],vt:[],vn:[]};          // payload after the keyword, verbatim
  const body=[];                               // {kind:'line'|'face'} in source order
  const ids=new Map();                         // "vi/vti/vni" -> output index (1-based)
  for(const raw of lines){
    const line=raw.replace(/\r$/,'');
    if(!line) continue;
    const sp=line.indexOf(' ');
    const head=sp>=0?line.slice(0,sp):line;
    if(head==='v'||head==='vt'||head==='vn'){ poolText[head].push(line.slice(sp+1).trim()); continue; }
    body.push({kind:head==='f'?'face':'line',text:line});
  }
  const outV=[], outVt=[], outVn=[];
  const NO_UV=(params.untexturedUv||[0,0]).join(' ');
  const NO_NORMAL=[0,0,1].join(' ');
  const idOf=(ref)=>{
    const key=ref[0]+'/'+ref[1]+'/'+ref[2];
    let id=ids.get(key);
    if(id!==undefined) return id;
    id=outV.length+1;
    ids.set(key,id);
    outV.push(poolText.v[ref[0]-1]!==undefined?poolText.v[ref[0]-1]:'0 0 0');
    outVt.push(ref[1]&&poolText.vt[ref[1]-1]!==undefined?poolText.vt[ref[1]-1]:NO_UV);
    outVn.push(ref[2]&&poolText.vn[ref[2]-1]!==undefined?poolText.vn[ref[2]-1]:NO_NORMAL);
    return id;
  };
  // Resolve every face into triangles of OUTPUT ids, in file order, before serialising anything: the
  // pools have to be written ahead of the faces, so the numbering must be settled first.
  const faces=body.filter(e=>e.kind==='face').map(entry=>{
    const refs=entry.text.trim().split(/\s+/).slice(1).map(r=>{
      const p=r.split('/');
      return [parseInt(p[0],10)||0, p[1]?parseInt(p[1],10)||0:0, p[2]?parseInt(p[2],10)||0:0];
    });
    const tris=[];
    for(let i=1;i+1<refs.length;i++) tris.push([refs[0],refs[i],refs[i+1]].map(idOf));
    return tris;
  });
  const out=[];
  for(const entry of body) if(entry.kind==='line'&&/^mtllib\s/.test(entry.text)) out.push(entry.text);
  for(const t of outV) out.push('v '+t);
  for(const t of outVt) out.push('vt '+t);
  for(const t of outVn) out.push('vn '+t);
  let next=0;
  for(const entry of body){
    if(entry.kind==='line'){ if(!/^mtllib\s/.test(entry.text)) out.push(entry.text); continue; }
    for(const tri of faces[next++]) out.push('f '+tri.map(i=>i+'/'+i+'/'+i).join(' '));
  }
  return out.join('\n')+'\n';
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
    // The anchor's normal is the REFERENCE FACE's own normal (docs §1.3⑨: "法线只取面积最大那个面").
    //
    // It used to be a three-vertex cross about the GROUP's centroid, which is only the reference face's
    // normal while the group is flat. On a folded group the centroid sits off the reference plane, so
    // that cross points somewhere else entirely - measured on BR101's three-panel dashboard it was
    // 49.3 degrees off the reference face. Three things then went wrong at once, all silently:
    //   · the anchor's normal/up/right were wrong (up is orthogonalised against the normal, so a 49
    //     degree error dragged the written `up` from (0, 0.98, -0.20) to (-0.22, 0.50, -0.84));
    //   · buildFacets' winding-consistency guard compares the reference facet's normal against this
    //     one and bailed out, so the folded dashboard got NO facet data and fell back to the single
    //     misaligned quad the facet path exists to replace;
    //   · the 2D domain of every windshield was built on the same tilted normal (5.8 degrees on
    //     BR101), so the packager's wiper fit and the independent verifier disagreed by 3-27 mm.
    // The Newell normal of the reference face about its OWN centroid has none of those problems, and
    // on a flat group it is the same direction to within rounding (so flat anchors do not move).
    const refFaceInfo=faceNormalArea(f0.map(rc));
    // (a degenerate reference face makes faceNormalArea fall back to +Z; keep the old three-vertex
    //  cross there so such a group behaves exactly as it did before)
    let n=refFaceInfo.area>1e-12 ? refFaceInfo.n : nz(cr(sub(rc(f0[1]),c), sub(rc(f0[2]),c)));
    // Blender face winding decides the normal. Flip it globally with flipAnchorNormal, or per anchor
    // with flipAnchorNormalByGroup: ["mmtr_hud", ...] (matches the object name or the anchor name).
    const rawNameForFlip=(g.name||'').replace(/\.\d+$/,'');
    const anchorNameForFlip=rawNameForFlip.replace(/^mmtr_/,'');
    const flipList=params.flipAnchorNormalByGroup||[];
    const flipThis=params.flipAnchorNormal||flipList.includes(rawNameForFlip)||flipList.includes(anchorNameForFlip);
    if(flipThis) n=n.map(x=>-x);
    // up = the quad edge most aligned with world +Y, orthogonalised against the normal;
    // right = up x normal (right-handed frame: right = HUD 右, up = HUD 上, normal = HUD 朝向)
    //
    // ONLY THE NORMAL IS ORIENTED; up IS LEFT ALONE. Flipping up as well would flip right twice (right =
    // up x normal) and flipping up alone would flip right once, so the two panes of a cab would end up on
    // opposite handednesses - measured: that alone put the verifier 20 checks out (pivotU, parkAngle,
    // sweepSign and the blade ends all swapped between the two panes of each cab).
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
    // mmtr_light_<cab>_<n> - 驾驶室 <cab> 的第 <n> 盏灯（车灯锚点，纯数据：面被剥掉、不进几何）。
    //   mmtr_light_1_1 = 驾驶室1 的主前照灯；_2/_3 = 标志灯/近照灯。
    // 法线 = 灯射出的方向（由顶点绕序决定），面中心 = 灯罩中心，面尺寸 = 灯罩大小。
    // mmtr_pid_<cab>[_<n>] - 水牌（PID：车次/班次号 + 本趟终点站）。面中心 = 牌面中心，
    //   法线朝**车外**（旅客站在站台上要看得见），面尺寸 = 牌面大小，up = 文字的上方向。
    //   同一端可以有多块（mmtr_pid_1_1 / mmtr_pid_1_2 = 两侧各一块），内容由客户端按端决定。
    // mmtr_next_<cab>[_<n>] - 下一站牌（车内显示屏：写"下一站 X"）。约定与 mmtr_pid_* 相同，
    //   只是显示的字段不同；两族都是纯数据锚点（面被剥掉，牌底与文字由客户端画）。
    //   为什么不合成一族："写什么"是模型的意图，写进名字里比再加一层配置可读；
    //   只有 pid 没有 next 的车（或反过来）都是合法的。
    // mmtr_face_<cab>[_<n>] - **动态面**（notes/359）：一块"画什么由锚点 JSON 的 faces 段说"的屏。
    //   与水牌同一套约定（面中心 = 屏幕中心、法线朝读它的人、面尺寸 = 屏幕大小、up = 文字上方向），
    //   但内容不再是"写死的那两行"，而是 faces 段里的一段文档（元素 / 条件 / 模板）。
    //   ★ 面文档的键就是这里的锚点名（face_1 / face_1_2 / …）—— 见 docs 的《车辆动态面-作者指南》。
    const m=/^(hud|seat|cabdoor|ack|light|pid|next|face)(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
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
    // mmtr_wipersweep_<cab>_<pane>[_<wiper>] - the sector ONE wiper sweeps. The third index says WHICH
    // wiper of that glass it is and is absent (= 1) for a glass with a single wiper, so every existing
    // model parses exactly as before. Two wipers on one screen is therefore
    //   mmtr_windshield_1_1  +  mmtr_wipersweep_1_1  +  mmtr_wipersweep_1_1_2
    // (one glass, two fans, two pivots) rather than two glasses - see docs §1.4②.
    const swm=/^wipersweep(?:_(\d+))?(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    const kind=wsm?'windshield':(swm?'wipersweep':(m?m[1]:rawName));
    const cab=wsm?(wsm[1]?+wsm[1]:1):(swm?(swm[1]?+swm[1]:1):(m&&m[2]?+m[2]:null));
    const pane=(wsm||swm)?((wsm?wsm[2]:swm[2])?+(wsm?wsm[2]:swm[2]):1):null;
    // Which wiper of that glass this fan belongs to. Windshield anchors have no wiper index.
    const wiper=swm?(swm[3]?+swm[3]:1):null;
    const door=(wsm||swm)?null:(m&&m[3]?+m[3]:null);
    const anchor={name:rawName,kind:kind,cab:cab,door:door,car:params.carIndex||0,
      x:+c[0].toFixed(5), y:+c[1].toFixed(5), z:+c[2].toFixed(5),
      normal:n.map(x=>+x.toFixed(6)), up:up.map(x=>+x.toFixed(6)), right:right.map(x=>+x.toFixed(6)),
      widthM:+(wMax-wMin).toFixed(4), heightM:+(hMax-hMin).toFixed(4)};
    // WHICH lamp of the cab this is: 1 = main headlight, 2+ = marker / near lamp.
    // Written under its own name (the `door` field happens to hold the same number, but its meaning is
    // door-specific); absent means 1, so a single-lamp-per-cab model keeps the old reading.
    // The pid/next boards use it the same way: the ORDINAL of the board on that cab (both sides of a
    // cab are separate boards showing the same text), and the client keys its texture by anchor name.
    if(kind==='light'||kind==='pid'||kind==='next'||kind==='face') anchor.index=(door===null?1:door);
    // The pane number is written ONLY when it is not 1, so a single-pane glass (which is what every
    // model built before multi-pane existed has) keeps a byte-identical anchor entry. Absent = pane 1.
    if(pane!==null && pane!==1) anchor.pane=pane;
    // Same rule for the wiper index: written ONLY when it is not 1, so a single-wiper glass keeps a
    // byte-identical anchor entry.
    if(wiper!==null && wiper!==1) anchor.wiper=wiper;
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
    // ---- SAG PROFILE: how far the modelled surface departs from this anchor's plane ----------------
    //
    // The anchor's frame comes from ONE face (the largest), which is exact while the group is flat and
    // silently wrong when it is not: a CURVED mmtr_windshield (V25 subdivided it into 33 faces) is then
    // treated as the plane of whichever patch happened to be biggest, and the client draws the whole rain
    // layer on it. Measured on BR101 V25: the real glass sits -55.2 .. +112.1 mm from that plane, against
    // a water layer offset of 50 mm and a glass thickness of 23 mm - the drops float a hand's width off
    // the screen, and NOTHING says so (both anchor verifiers PASS, because neither has a notion of
    // "curved").
    //
    // A windscreen is usually a CYLINDER, and a cylinder unrolls flat with NO distortion - so the whole
    // 2-D rain model (density, wipe test, transport, wiper kinematics) stays exact and only the
    // (u,v) -> 3-D mapping needs the correction.
    //
    // Emitted ONLY when the surface is measurably non-planar, so every flat model's anchor stays
    // byte-identical (docs §1.3: flat anchors must not move).
    if(kind==='windshield'){
      // A 2-D GRID RATHER THAN A 1-D PROFILE. A windscreen is usually a cylinder, which a profile along
      // up would capture exactly - but only against a frame whose normal is perpendicular to the
      // cylinder's axis, and nothing guarantees that: measured on BR101 V25, even with the surface's own
      // area-weighted normal a 17-sample profile still leaves 18.3 mm, because part of the departure is
      // linear across the WIDTH. Fitting the frame until the surface happens to come out one-dimensional
      // would break the next time the model changes; a coarse grid assumes nothing and reproduces a
      // smooth surface to well under a millimetre at this resolution.
      const SAG_NX=17, SAG_NY=17, SAG_FLAT_MM=1.0, SAG_WARN_MM=2.0;
      // NODE VALUES, NOT BIN MEANS. Sampling each bin's average and reading it back bilinearly loses half
      // a cell of slope at the edges of the face, and the sag changes by 23 mm across one cell at its
      // steep end: measured on BR101 V25, a vertex on the bottom edge reads 51.9 mm while its own bin
      // averages 29 mm. So each NODE takes an inverse-distance-weighted value from the surface's own
      // vertices - exact where a node lands on a vertex, and for the ruled strip this windscreen actually
      // is (68 vertices, all of them on the two side edges, nothing in between) it reproduces the straight
      // line between those edges, which is what the surface is.
      const uSpan=Math.max(1e-9,wMax-wMin), vSpan=Math.max(1e-9,hMax-hMin);
      const pts=[];
      for(const v of p){
        const d=sub(v,c);
        pts.push([dot(d,right), dot(d,up), dot(d,n)]);
      }
      const grid=new Array(SAG_NX*SAG_NY).fill(0);
      const NEAREST=6;
      const best=new Array(NEAREST), bestD=new Array(NEAREST);
      for(let iy=0;iy<SAG_NY;iy++){
        const nodeV=hMin+vSpan*iy/(SAG_NY-1);
        for(let ix=0;ix<SAG_NX;ix++){
          const nodeU=wMin+uSpan*ix/(SAG_NX-1);
          for(let k=0;k<NEAREST;k++){ best[k]=null; bestD[k]=Infinity; }
          for(const q of pts){
            const dd=(q[0]-nodeU)*(q[0]-nodeU)+(q[1]-nodeV)*(q[1]-nodeV);
            for(let k=0;k<NEAREST;k++){
              if(dd<bestD[k]){ for(let m=NEAREST-1;m>k;m--){ bestD[m]=bestD[m-1]; best[m]=best[m-1]; } bestD[k]=dd; best[k]=q; break; }
            }
          }
          let num=0, den=0;
          for(let k=0;k<NEAREST;k++){
            if(!best[k]) continue;
            const w=1/(bestD[k]+1e-12);
            num+=w*best[k][2]; den+=w;
          }
          grid[iy*SAG_NX+ix]=den>0?num/den:0;
        }
      }
      let sagLo=Infinity, sagHi=-Infinity;
      for(const x of grid){ sagLo=Math.min(sagLo,x); sagHi=Math.max(sagHi,x); }
      const sagSpanMm=(sagHi-sagLo)*1000;
      if(sagSpanMm>SAG_FLAT_MM){
        anchor.sagGridM=grid.map(x=>+x.toFixed(6));
        anchor.sagUMinM=+wMin.toFixed(6); anchor.sagUMaxM=+wMax.toFixed(6);
        anchor.sagVMinM=+hMin.toFixed(6); anchor.sagVMaxM=+hMax.toFixed(6);
        // Never silent: a curved glass changes where the rain is drawn, so it is reported either way.
        console.log('  curved glass: '+(g.name||'')+' departs from its anchor plane by '+sagSpanMm.toFixed(1)+' mm'+
          (sagSpanMm>SAG_WARN_MM?' (sagGridM '+SAG_NX+'x'+SAG_NY+' over right '+(wMax-wMin).toFixed(3)+' m x up '+
           (hMax-hMin).toFixed(3)+' m)':''));
      }
    }
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
      // same client code as a car's - see fitWiperMechanism. The wiper index picks WHICH of the glass's
      // wiper part sets belongs to this fan.
      const mechanism=fitWiperMechanism(fit, glass, DOMAINS.get(glass.name), sweep.wiper||1);
      if(mechanism) for(const key of Object.keys(mechanism)) fit[key]=mechanism[key];
      // Two fans claiming the same wiper of the same glass is an authoring error and would be silent
      // otherwise: the second fit would overwrite the first and one wiper would sweep the other's sector.
      if(SWEEP_FITS.has(sweep.name)){
        console.warn('WARNING: two fans are named for wiper '+(sweep.wiper||1)+' of '+glass.name+
          ' - '+sweep.name+' replaces the earlier one. Name the second wiper '+sweep.name+'_2.');
      }
      SWEEP_FITS.set(sweep.name, fit);
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
// KEEP THIS IN STEP WITH MmtrWindshield.WindshieldConfig / WiperConfig. A field the client reads but this
// list omits is DROPPED AT PACK TIME and the config looks like it was ignored in game - that was the real
// cause of the "sweepSign has no effect" bug (notes/179 §9.4 #3), and the "droplet physics has no effect"
// repeat of it.
//
// THE FIELDS ARE SPLIT IN TWO, and the split is what makes several wipers on ONE glass expressible:
//
//   PER_WIPER_FIELDS - the blade and its mechanism. One set per wiper, so two wipers on one screen can
//     have different pivots, park directions, strokes and speeds. A glass with ONE wiper keeps writing
//     these FLAT (byte-identical to every pack made before multi-wiper existed); a glass with two or more
//     writes them inside "wipers": [ {..}, {..} ] with an explicit "wiperIndex" on each entry.
//   GLASS_FIELDS - the weather and the water. There is exactly ONE bead field per glass, so these are
//     shared by every wiper on it: two blades clear the same rain.
const PER_WIPER_FIELDS=['wiper','drawBlade','dualWiper','armM','parkAngleDeg','sweepDeg','sweepSign','periodS',
                         'pivotU','pivotV','bladeWidthM','colour','armColour',
                         'pivot2U','pivot2V','bladeAU','bladeAV','bladeBU','bladeBV','pinAU','pinAV','pinBU','pinBV'];
const GLASS_FIELDS=['raindrops','fallMps','maxStreakM','snow','waterOffsetM','minVisibleRadiusM','maxVisibleRadiusM',
                    'creepMps','jitterMps','minBeadRadiusM','maxBeadRadiusM','growthMps','spawnPopulationPerSecond',
                    'staticThresholdM','densityCellM','densityThresholdPerM2','runoffMps','pushM','mergeDistanceM',
                    'collectZoneM'];
const WINDSHIELD_FIELDS=PER_WIPER_FIELDS.concat(GLASS_FIELDS);
function buildWindshieldConfig(){
  const byAnchor={};
  /** Glass anchor name -> the AUTHORED per-wiper override list ("wipers": [...]) of that glass. */
  const authoredWipers={};
  const warnUnknown=(key,entry,allowed,where)=>{
    for(const field of Object.keys(entry||{})){
      if(field==='anchor'||field==='index'||field==='wipers') continue;
      if(!allowed.includes(field)){
        console.warn(`windshield["${key}"]${where}: unknown field "${field}" will NOT reach the client - add it to WINDSHIELD_FIELDS/PER_WIPER_FIELDS if it is meant to do something`);
      }
    }
  };
  const put=(key,entry)=>{
    const values={};
    for(const field of WINDSHIELD_FIELDS){
      if(entry&&entry[field]!==undefined) values[field]=entry[field];
    }
    // Name anything that is about to be dropped, so the next new field is caught here instead of in game.
    warnUnknown(key,entry,WINDSHIELD_FIELDS,'');
    if(entry&&entry.wipers!==undefined){
      if(Array.isArray(entry.wipers)){
        // Validated element-wise: a typo inside a per-wiper block would otherwise be dropped in silence,
        // which is the exact failure this list exists to prevent.
        authoredWipers[key]=entry.wipers.map((one,index)=>{ warnUnknown(key,one,PER_WIPER_FIELDS,'.wipers['+index+']'); return one||{}; });
      } else {
        console.warn(`windshield["${key}"].wipers is not an array, ignored`);
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
    // Every fan over THIS glass, by wiper index. A fan belongs to the glass whose cab AND pane match, and
    // its own third index says which wiper of that glass it is (absent = 1).
    const fits=[];
    for(const sweep of anchors){
      if(sweep.kind!=='wipersweep') continue;
      if(sweep.cab!==anchor.cab||(sweep.pane||1)!==(anchor.pane||1)) continue;
      const fit=SWEEP_FITS.get(sweep.name);
      if(fit) fits.push({wiper:sweep.wiper||1, fit:fit});
    }
    fits.sort((a,b)=>a.wiper-b.wiper);

    if(fits.length===1&&fits[0].wiper===1){
      // ---- ONE wiper: the flat block, byte-identical to every pack made before multi-wiper existed ----
      // The modelled sector fills in whatever the config did not state outright, so an explicit
      // parkAngleDeg in the pack config still wins (that is the escape hatch for tuning).
      // Only real config fields are copied: the fit also carries internal values (the fan's boundary
      // lines) that have no business in the pack.
      for(const field of Object.keys(fits[0].fit)) if(WINDSHIELD_FIELDS.includes(field)&&values[field]===undefined) values[field]=fits[0].fit[field];
      // A modelled SOLID wiper replaces the drawn blade. It must NOT switch the wiper off: the glass
      // still has to be wiped, and the modelled arm is what the animation is meant to move.
      // This is the ONLY case in which a client default is overridden, which is what keeps every model
      // packed before solid wipers existed drawing exactly what it drew before.
      if(wiperGroups.includes(wiperPartName('wiper',anchor.cab,anchor.pane||1,1))&&values.drawBlade===undefined) values.drawBlade=false;
      continue;
    }

    if(fits.length===0){
      // No fan at all: whatever the author wrote stands, including the blade decision keyed on wiper 1.
      if(wiperGroups.includes(wiperPartName('wiper',anchor.cab,anchor.pane||1,1))&&values.drawBlade===undefined) values.drawBlade=false;
      continue;
    }

    // ---- TWO OR MORE wipers on one glass ------------------------------------------------------------
    // The per-wiper fields MOVE into "wipers"; the glass-level ones (the weather and the water) stay
    // where they are, because there is one bead field per glass and every blade clears that same rain.
    // Whatever the author wrote flat is a DEFAULT for every wiper (so parkAngleDeg can be set once), and
    // an authored "wipers": [..] overrides the i-th wiper. An explicit wiperIndex travels with each entry
    // so a non-contiguous set (say wiper 1 and wiper 3) can never be silently renumbered.
    const defaults={};
    for(const field of PER_WIPER_FIELDS){
      if(values[field]!==undefined){ defaults[field]=values[field]; delete values[field]; }
    }
    const authored=authoredWipers[anchor.name]||[];
    if(authored.length&&authored.length!==fits.length){
      console.warn('WARNING: '+anchor.name+' has '+fits.length+' modelled wipers but '+authored.length+
        ' authored "wipers" entries; the model wins and the extra authored entries are ignored.');
    }
    values.wipers=fits.map((entry,index)=>{
      const one={wiperIndex:entry.wiper};
      for(const field of PER_WIPER_FIELDS) if(defaults[field]!==undefined) one[field]=defaults[field];
      const override=authored[index];
      if(override) for(const field of PER_WIPER_FIELDS) if(override[field]!==undefined) one[field]=override[field];
      for(const field of Object.keys(entry.fit)) if(PER_WIPER_FIELDS.includes(field)&&one[field]===undefined) one[field]=entry.fit[field];
      if(one.wiper===undefined) one.wiper=true;
      // Each wiper decides for ITSELF whether the drawn blade is suppressed: only the one the model
      // actually carries a solid blade for.
      if(wiperGroups.includes(wiperPartName('wiper',anchor.cab,anchor.pane||1,entry.wiper))&&one.drawBlade===undefined) one.drawBlade=false;
      return one;
    });
    console.log('windshield '+anchor.name+': '+values.wipers.length+' wipers on one glass (wipers '+
      values.wipers.map(w=>w.wiperIndex).join(', ')+')');
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
    // ⚠ 这个正则必须与主路径的那个（buildAnchors 里的 `m`）保持一致：sidecar 与 OBJ 两条路
    // 各写一份，漏改一边的后果是"同一个名字，从这边进来 kind 对、从那边进来 kind 是整串"
    // （客户端只认字面量 "pid"/"next"/"face"，整串会静默变成 OTHER）。
    const m=/^(hud|seat|cabdoor|ack|light|pid|next|face)(?:_(\d+))?(?:_(\d+))?$/.exec(rawName);
    const wsm=/^windshield(?:_(\d+))?$/.exec(rawName);
    anchors.push({name:rawName,kind:a.kind||(wsm?'windshield':(m?m[1]:rawName)),cab:a.cab!==undefined?a.cab:(wsm?(wsm[1]?+wsm[1]:1):(m&&m[2]?+m[2]:null)),door:a.door!==undefined?a.door:(wsm?null:(m&&m[3]?+m[3]:null)),index:a.index!==undefined?a.index:(m&&m[3]?+m[3]:1),car:params.carIndex||0,
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
function isDoorwaySource(name){
  // ★ 2026-09-30：门的**玻璃伴随组** `<门叶>_glass` 不是门叶 —— 它跟着门叶滑（见下面的部件表），
  //   但不需要再多一个门洞薄片（多出来的薄片和门叶那块完全重合，只会让 D4 检查与部件表变乱）。
  if(name.endsWith('_glass')) return false;
  return name.startsWith('door_l')||name.startsWith('door_r')||extraDoorwayNames.has(name);
}
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
// floorTopY records the slab's TOP surface (the plane a rider's feet belong on) for the rider-offset
// computation below, which has to tell MTR where the synthetic floor is.
let floorTopY=null;
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
  floorTopY=fy+th;
}
const objRaw=extraGeom.length?objMain+'\n'+extraGeom.join('\n'):objMain;
// LAST STEP, and the one MTR actually depends on: un-weld + triangulate into the index-locked layout
// MTR's ObjModelLoader requires (#v == #vt == #vn, `f i/i/i`, triangles only). See toMtrObj's doc.
const objFinal=toMtrObj(objRaw);
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
  const mmtrManifest=path.join(soundSrc,'mmtr_traction.json');
  const mmtrSounds=path.join(soundSrc,'mmtr_traction.sounds.json');
  if(fs.existsSync(mmtrManifest)){
    // MMTR 自研牵引音集（notes/377/378）：事件 id 与文件路径都由烘焙器写进清单，
    // 连同现成的 sounds.json 片段一起产出。这里**只做合并** ——
    // 打包器不需要懂清单格式，清单以后加字段也不必回来改这里。
    if(!fs.existsSync(mmtrSounds)) throw new Error('soundDir 里有 mmtr_traction.json 但缺 mmtr_traction.sounds.json：'+soundSrc);
    const fragment=JSON.parse(fs.readFileSync(mmtrSounds,'utf8'));
    let count=0;
    for(const id of Object.keys(fragment)){
      const def=fragment[id];
      const name=def&&def.sounds&&def.sounds[0]&&def.sounds[0].name;
      // 登记前核对 ogg 真的在包里：登记了却缺文件 = 进游戏只有一行"unknown soundEvent"日志 + 静音
      const file=name&&name.split('/').pop();
      if(name&&file&&available.has(file.toLowerCase())){ soundNames[id]=name; count++; }
      else console.warn('  [warn] mmtr 音效事件 '+id+' 指向的 ogg 不在 soundDir 里，已跳过：'+name);
    }
    console.log('mmtr sound set: '+soundBase+' events='+count+'（来自 mmtr_traction.sounds.json）');
  } else if(!fs.existsSync(cfgPath)){
    throw new Error('soundDir 里既没有 mmtr_traction.json（MMTR 牵引音）也没有 sound.cfg（BVE 音效集）：'+soundSrc);
  } else {
    for(const line of fs.readFileSync(cfgPath,'utf8').split(/[\r\n]+/)){
      const clean=line.trim().replace(/\s*(;|#|\/\/).+$/,'');
      const m=/^(.+?)=(.*)$/.exec(clean);
      if(!m) continue;
      const name=m[2].trim().toLowerCase().replace(/\\/g,'/').replace(/\.wav|\s|.+\//g,'');
      if(name&&available.has(name)) soundNames[soundBase+'_'+name]='mtr:'+soundBase+'/'+name;
    }
  }
}
// MTR builds NO floor boxes for OBJ models.
//
// VehicleResource.writeFloorsAndDoorways collects FLOOR/DOORWAY boxes only on the Blockbench path
// (ModelPropertiesPart.writeCache(BlockbenchModel...) handles NORMAL/FLOOR/DOORWAY); the OBJ overload
// only handles NORMAL. So an OBJ vehicle always ends up with floors.isEmpty() and MTR substitutes a
// SYNTHETIC slab:  y = 1 + legacyRiderOffset, spanning the declared car width/length
// (VehicleResource.java: "No floors or doorways found in vehicle models" in the log is this firing).
//
// That slab is what VehicleRidingMovement clamps the rider onto. Its Y has to land where this model's
// rider actually is, otherwise the clamp finds no box under the cab and throws the ride away the moment
// it is taken - which is exactly how cab entry failed on the BR101 (synthetic floor at y=1.0, cab at
// y=2.32, and the clamp's nearest-box fallback only tolerates 1 m).
//
// Aim the CAMERA at the modelled eye point: the rider's entity origin (their feet) sits the player's eye
// height below mmtr_seat, so a seated cab lands the eyes on the anchor instead of under the desk. Falls
// back to the generated floor's top, then to MTR's own default.
const EYE_HEIGHT_M=1.62;
const seatAnchor=anchors.find(a=>a.kind==='seat');
const riderFeetY=seatAnchor?seatAnchor.y-EYE_HEIGHT_M:(floorTopY!==null?floorTopY:null);
const legacyRiderOffset=params.legacyRiderOffset!==undefined?params.legacyRiderOffset:(riderFeetY===null?0:riderFeetY-1);
console.log('rider offset: legacyRiderOffset='+legacyRiderOffset.toFixed(4)
  +(seatAnchor?' (mmtr_seat '+seatAnchor.name+' eye '+seatAnchor.y.toFixed(3)+' - eye height '+EYE_HEIGHT_M+')':' (no seat anchor; using floor top '+(floorTopY===null?'default':floorTopY.toFixed(3))+')')
  +' -> MTR synthetic floor y='+(1+legacyRiderOffset).toFixed(3));
// json entry
const length=params.carLengthBlocks||15, width=params.carWidthBlocks||5;
const bc=params.bogieCount||2;
let b1=params.bogieOffsetBlocks, b2=params.bogie2OffsetBlocks;
if(b1===undefined&&bc===2){ b1=-length/2+1.5; b2=length/2-1.5; } if(b1===undefined)b1=0; if(b2===undefined)b2=0;
const custom={vehicles:[{id:id,name:params.name||id,color:params.color||'7FA8CC',transportMode:params.transportMode||'TRAIN',length:length,width:width,bogie1Position:b1,bogie2Position:b2,couplingPadding1:params.couplingPadding1||0,couplingPadding2:params.couplingPadding2||0,legacyRiderOffset:legacyRiderOffset,bveSoundBaseResource:soundBase||undefined,models:[{modelResource:'mtr:'+id+'/'+srcBase,textureResource:'minecraft:textures/misc/white.png',modelPropertiesResource:'mtr:properties_'+id+'.json',positionDefinitionsResource:'mtr:definition_'+id+'.json',flipTextureV:params.flipTextureV!==false}]}],signs:[],rails:[],objects:[],lifts:[]};
const parts=[]; const slide=params.doorSlidePx!==undefined?params.doorSlidePx:14;
if(used.body) parts.push({names:['body'],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
// params.interiorRenderStage —— 内装默认走 INTERIOR_TRANSLUCENT（半透明内装玻璃那一档，底层
// getEntityTranslucentCull = **有背面剔除**）。MTR 官方车的内装是 INTERIOR（**不透明** CUTOUT_BRIGHT），
// 窗户就是**什么都不放的洞**。若模型把内装（座椅/墙板/操纵台）放进 interior 组、窗口另外单独做玻璃，
// 就该显式写 "interiorRenderStage": "INTERIOR"，否则内装会被当半透明件画（见 notes/345 §7.9/§7.11）。
if(used.interior) parts.push({names:['interior'],positionDefinitions:['p0'],renderStage:params.interiorRenderStage||'INTERIOR_TRANSLUCENT',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
// One part per door group: each numbered door slides on its own. Direction defaults to +slide on the
// left / -slide on the right; override per door with params.doorSlideByGroup: {"door_l_2": -14, ...}.
// ★ 2026-09-30 门的玻璃（用户口径："游戏内玻璃不会跟着门一起打开"）：
//   MTR 的动画单位是**部件**，而部件由 OBJ 组名决定。玻璃原来在独立的 `glass` 部件里
//   （doorZMultiplier 0）⇒ 门开了玻璃不动。把玻璃并进门叶部件也不行：一个部件只能有一个
//   renderStage，而门叶是写死的 EXTERIOR，INTERIOR 系列是 CUTOUT_BRIGHT（全亮）⇒ 整扇门不受光。
//   所以：玻璃**单独成件**，但用**同一个 doorZMultiplier** ⇒ 与门叶同步滑、又保住
//   INTERIOR_TRANSLUCENT（半透明玻璃）。
//   命名约定：`<门叶组名>_glass`（滑向自动继承门叶在 doorSlideByGroup 里的值 ⇒ 配置不用改）。
const doorGlassSet=new Set();
for(const g of doorGroups) if(doorGroups.includes(g+'_glass')) doorGlassSet.add(g+'_glass');
const leafGroups=doorGroups.filter(g=>!doorGlassSet.has(g));
for(const g of leafGroups){
  const isLeft=g.startsWith('door_l');
  const ov=params.doorSlideByGroup&&params.doorSlideByGroup[g]!==undefined?params.doorSlideByGroup[g]:undefined;
  const mult=ov!==undefined?ov:(isLeft?slide:-slide);
  parts.push({names:[g],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:mult,doorAnimationType:params.doorAnimationType||'STANDARD'});
  const gg=g+'_glass';
  if(doorGlassSet.has(gg)){
    parts.push({names:[gg],positionDefinitions:['p0'],renderStage:'INTERIOR_TRANSLUCENT',doorXMultiplier:0,doorZMultiplier:mult,doorAnimationType:params.doorAnimationType||'STANDARD'});
  }
}
if(doorGlassSet.size) console.log('door glass companions: '+[...doorGlassSet].join(', ')+' (sliding with their leaves)');
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
// ---- per-role render stage / condition (车灯、随状态显隐的部件) ------------------------
// MTR decides TWO separate things about a part (source: resource/ModelPropertiesPart.java):
//   condition   -> whether the part is DRAWN AT ALL (`render()` returns early when it is false;
//                  it is not "hidden", the geometry is never submitted)
//   renderStage -> which shader + queued render layer + LIGHT VALUE it gets when it is drawn
//                  (RenderStage.java: LIGHT=CUTOUT_GLOWING/LIGHT, ALWAYS_ON_LIGHT=TRANSLUCENT_GLOWING
//                  /LIGHT_2, INTERIOR=CUTOUT_BRIGHT/INTERIOR, ... ; both light stages pass
//                  getDefaultLight() = 0xF000F0, i.e. NO world light and NO directional shading)
// A group can only ever be ONE renderStage, so anything that must glow (or must switch off when the
// car runs the other way) has to be its OWN OBJ group - mapping a lamp into "body" can only ever
// produce an EXTERIOR part that is always drawn and never lights up.
//   "partSpecs": {
//     "headlights":  [ {"renderStage":"ALWAYS_ON_LIGHT","condition":"MMTR_LAMP"} ],
//     "tail_lights": [ {"renderStage":"ALWAYS_ON_LIGHT","condition":"ON_ROUTE_BACKWARDS"},
//                      {"renderStage":"ALWAYS_ON_LIGHT","condition":"AT_DEPOT"} ]
//   }
// SEVERAL entries for one role = several parts for the SAME group name, which is exactly how stock
// MTR writes "tail lights when running backwards OR parked" (properties/vehicle/s_train_head_1.json).
//
// ★ MMTR_LAMP（2026-10-03，notes/374）：**MMTR 自己的条件值** —— "这一组几何是车灯灯罩"。
//   它一直是画的，颜色由客户端逐 draw 给（端 + 档位 = 近光/远光白、尾灯红、关闭暗，
//   见 MmtrHeadlights.lampColor）。想要 MMTR 那种"同一块灯罩既当近光远光、也当红尾灯"的车，
//   就写这个值；写 ON_ROUTE_FORWARDS/BACKWARDS 是上游 MTR 的"两个罩子配对"口径，MMTR 的灯不这么用。
const RENDER_STAGES=['EXTERIOR','LIGHT','ALWAYS_ON_LIGHT','INTERIOR','INTERIOR_TRANSLUCENT'];
const PART_CONDITIONS=['NORMAL','AT_DEPOT','ON_ROUTE_FORWARDS','ON_ROUTE_BACKWARDS','DOORS_CLOSED','DOORS_OPENED','CHRISTMAS_LIGHT_RED','CHRISTMAS_LIGHT_YELLOW','CHRISTMAS_LIGHT_GREEN','CHRISTMAS_LIGHT_BLUE','MMTR_LAMP'];
const partSpecs=params.partSpecs||{};
const stagedParts=[];
for(const role of Object.keys(partSpecs)){
  if(!used[role]){
    // The part would name a group the OBJ does not contain -> MTR draws nothing, silently.
    console.warn('WARNING: partSpecs names role "'+role+'" but no OBJ group maps to it - the part will be empty and the lights will simply not exist.');
  }
  const list=Array.isArray(partSpecs[role])?partSpecs[role]:[partSpecs[role]];
  for(const spec of list){
    const rs=spec.renderStage||'EXTERIOR', cd=spec.condition===undefined||spec.condition===null||spec.condition===''?null:spec.condition;
    if(RENDER_STAGES.indexOf(rs)<0) throw new Error('partSpecs["'+role+'"].renderStage "'+rs+'" is not one of '+RENDER_STAGES.join('/'));
    if(cd!==null&&PART_CONDITIONS.indexOf(cd)<0) throw new Error('partSpecs["'+role+'"].condition "'+cd+'" is not one of '+PART_CONDITIONS.join('/'));
    const part={names:[role],positionDefinitions:['p0'],renderStage:rs,doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'};
    if(cd!==null) part.condition=cd;
    parts.push(part);
    stagedParts.push(role+'='+rs+(cd?'/'+cd:''));
  }
}
if(stagedParts.length) console.log('staged parts: '+stagedParts.join(', '));
// extras (matched groups beyond known) as EXTERIOR - "anchor" is data, never rendered.
// Roles written above via partSpecs are skipped here, otherwise they would ALSO get an EXTERIOR part.
for(const r of Object.keys(used)) if(!['body','interior','door_l','door_r','anchor'].includes(r)&&!partSpecs[r]) parts.push({names:[r],positionDefinitions:['p0'],renderStage:'EXTERIOR',doorXMultiplier:0,doorZMultiplier:0,doorAnimationType:'STANDARD'});
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
// ---- 玻璃：完全由 Blender 导出的 OBJ 与它的贴图决定（notes/345 §7.17）------------------
// MTR 的 OBJ 优化路径实际只做 cutout（α>0.1 实心、α<0.1 丢弃），所以【模型自己的玻璃材质】拿不到
// 半透明。这里把 `glass` 组的几何 + 该组材质的贴图导出给客户端，由客户端走**真混合**的层来画 ——
// 于是玻璃的观感（alpha/渐变/图案）完全由作者在 Blender 里画的贴图决定，mod 只提供通道。
//
// 两个必须守住的点：
//  ① UV 必须取 **staged（去焊接后）** 的那一份：MTR 采样是"第 i 个顶点配第 i 个 vt"，
//     原始 OBJ 的共享顶点 + 独立 vt 索引空间在这里会错位（notes/338 §7.3①）。
//  ② V 要按 flipTextureV 转成 MC 口径（OBJ v 向上、MC v 向下）。
function buildGlassPanes(){
  const objPath=path.join(sub,srcBase);
  if(!fs.existsSync(objPath)) return null;
  const positions=[],uvs=[],panes=[];let currentGroup=null,currentMaterial=null;const materials=new Set();
  const lines=fs.readFileSync(objPath,'utf8').split(/\r?\n/);
  for(const raw of lines){
    const t=raw.trim();
    if(t.startsWith('v ')){const p=t.split(/\s+/);positions.push([+p[1],+p[2],+p[3]]);}
    else if(t.startsWith('vt ')){const p=t.split(/\s+/);uvs.push([+p[1],+p[2]]);}
    else if(t.startsWith('usemtl '))currentMaterial=t.slice(7).trim();
    else if(t.startsWith('o ')||t.startsWith('g '))currentGroup=t.slice(2).trim();
    else if(t.startsWith('f ')&&currentGroup==='glass'){
      const face=[];
      for(const ref of t.split(/\s+/).slice(1)){
        const parts=ref.split('/');
        const vi=+parts[0]-1;
        const ti=parts.length>1&&parts[1]!==''?+parts[1]-1:-1;
        if(vi<0||vi>=positions.length)continue;
        const uv=ti>=0&&ti<uvs.length?uvs[ti]:[0.5,0.5];
        face.push({p:positions[vi],uv:uv});
      }
      if(face.length>=3){panes.push(face);if(currentMaterial)materials.add(currentMaterial);}
    }
  }
  if(!panes.length)return null;
  // staged OBJ 已三角化，而客户端只有"矩形 UV 贴到四边形"的绘制 API（IDrawing.drawTexture 无逐顶点 UV，
  // 见 notes/345 §7.17）⇒ 把相邻两个三角形重新配成四边形，并要求四角 UV 构成**轴对齐矩形**：
  // 这时矩形映射与逐顶点映射**完全等价**，图案不会走形。配不成（例如 41 边圆角窗）就跳过并报数。
  const quads=[];let skipped=0;
  for(let i=0;i+1<panes.length;i+=2){
    const a=panes[i], b=panes[i+1];
    if(a.length!==3||b.length!==3){skipped++;continue;}
    const key=e=>e.p.map(v=>v.toFixed(5)).join(',');
    const shared=a.filter(ea=>b.some(eb=>key(ea)===key(eb))).length;
    if(shared!==2){skipped++;i-=1;continue;}   // 不共享一条边 => 这两片不是同一个四边形的两半
    const uniq=[];
    for(const e of a.concat(b)) if(!uniq.some(u=>key(u)===key(e))) uniq.push(e);
    if(uniq.length!==4){skipped++;continue;}
    const us=uniq.map(e=>e.uv[0]), vs=uniq.map(e=>e.uv[1]);
    const u0=Math.min(...us), u1=Math.max(...us), v0=Math.min(...vs), v1=Math.max(...vs);
    const rectOk=uniq.every(e=>(Math.abs(e.uv[0]-u0)<1e-4||Math.abs(e.uv[0]-u1)<1e-4)
                            &&(Math.abs(e.uv[1]-v0)<1e-4||Math.abs(e.uv[1]-v1)<1e-4));
    // 允许"退化矩形"（四角 UV 同一点）：那是"从贴图取一个纹素"= 纯色玻璃，正是均匀色块贴图的正常用法 ✓
    if(!rectOk){skipped++;continue;}
    quads.push({
      positions:uniq.map(e=>[+e.p[0].toFixed(5),+e.p[1].toFixed(5),+e.p[2].toFixed(5)]),
      uv:[+u0.toFixed(5),+v0.toFixed(5),+u1.toFixed(5),+v1.toFixed(5)]
    });
  }
  if(!quads.length)return null;
  // 该组材质的 map_Kd -> 贴图名（并把它拷到 textures/vehicle/ 下，RenderLayer 只能从那里取图）
  const mtlName=srcBase.replace(/\.obj$/i,'.mtl');
  const mtlPath=path.join(sub,mtlName);
  let textureName=null;
  if(fs.existsSync(mtlPath)){
    let current=null;
    for(const raw of fs.readFileSync(mtlPath,'utf8').split(/\r?\n/)){
      const t=raw.trim();
      if(t.startsWith('newmtl '))current=t.slice(7).trim();
      else if(t.startsWith('map_Kd ')&&current&&materials.has(current)){textureName=t.slice(7).trim().split(/[\\/]/).pop();break;}
    }
  }
  let textureResource=null;
  if(textureName){
    const sourcePng=path.join(sub,textureName);
    if(fs.existsSync(sourcePng)){
      const targetDir=path.join(aMtr,'textures','vehicle');
      fs.mkdirSync(targetDir,{recursive:true});
      const targetName=id+'_glass.png';
      fs.copyFileSync(sourcePng,path.join(targetDir,targetName));
      textureResource='mtr:textures/vehicle/'+targetName;
    }
  }
  const flip=params.flipTextureV!==false;
  return {
    texture:textureResource,
    textureSource:textureName,
    flipTextureV:flip,
    quadCount:quads.length,
    skippedTriangles:skipped,
    quads:quads.map(q=>({
      positions:q.positions,
      // uv 矩形按 MC 口径：OBJ 的 v 向上、MC 的 v 向下 ⇒ v 取 1-v（区间随之翻转）
      uv:flip?[q.uv[0],+(1-q.uv[3]).toFixed(5),q.uv[2],+(1-q.uv[1]).toFixed(5)]:[q.uv[0],q.uv[1],q.uv[2],q.uv[3]]
    }))
  };
}
const glassPanes=buildGlassPanes();   // 没有 `glass` 组就返回 null（客户端回落成纯色玻璃）

if(anchors.length){
  // `rider.feetY` is the car-local height a rider's FEET end up at: MTR builds no floor boxes for OBJ
// models, so it substitutes a synthetic slab at y = 1 + legacyRiderOffset, and that is what
// VehicleRidingMovement clamps the rider onto. The client uses this value as the cab ENTRY height, so
// the entry lands exactly on the synthetic floor instead of relying on the clamp's 1 m tolerance - which
// is what capped how high the driver's eye point could be raised (the entry height comes from the door
// sill, and |syntheticFloor - sill| must stay within 1 m).
const anchorFile={anchors:anchors,hud:hudLayout||DEFAULT_HUD_LAYOUT,windshield:windshieldConfig,
  /*
   * notes/358 水牌版式（**每个车型独立**）：`params.pid` / `params.next` 原样写进锚点 JSON 的
   * `pid` / `next` 段，客户端按车型解析（MmtrPidLayout）。
   *
   * 为什么"只有作者写了才写"：没写 = 客户端用默认版式（班次号在上、站名在下，两行）—— 老包一个
   * 字节都不用改，行为与加这一层之前逐字相同（facets / sagGridM / panelFlipU 都是这条"缺省 = 旧行为"）。
   *
   * 版式里的数字全是**比例**（x/y 是牌面 0..1，size 是牌高的比例），所以同一份版式在不同尺寸的牌上
   * 自动等比 —— 这正是"不同车型不同尺寸与排版能各自独立"的原因。
   */
  pid:params.pid||undefined,
  next:params.next||undefined,
  /*
   * notes/359 **动态面**：`params.faces` 原样写进锚点 JSON 的 `faces` 段。
   *
   * <p>键 = **锚点名**（`pid_1` / `next_2` / `face_1_2` / …，即 groupRename 之后的那个名字），
   * 客户端按"这块锚点有没有文档"决定谁来画（有文档 = 面系统，没文档 = 老渲染器）。
   * 于是给已有锚点加一段文档就能把它换成动态面 —— 客户端一行代码都不用改。</p>
   *
   * <p>只有作者写了才写：没写 = 老行为（水牌走 pid/next 段的老版式），老包一个字节不用改。</p>
   */
  faces:params.faces||undefined,
  glass:glassPanes||undefined,
  rider:{feetY:+(1+legacyRiderOffset).toFixed(4)}};
  fs.writeFileSync(path.join(aMtr,'mmtr_anchors_'+id+'.json'), JSON.stringify(anchorFile));
  console.log('hud layout: '+(hudLayout?'authored':'default')+' widgets='+(anchorFile.hud.widgets||[]).length);
  console.log('pid layout: '+(params.pid?'authored rows='+((params.pid.rows||[]).length):'default')
    +' / next: '+(params.next?'authored rows='+((params.next.rows||[]).length):'default'));
  console.log('faces: '+(params.faces?('authored '+Object.keys(params.faces).length+' 块 ['+Object.keys(params.faces).join(', ')+'] —— 键必须是锚点名（打包日志的 anchors: 那行）'):'none'));
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
