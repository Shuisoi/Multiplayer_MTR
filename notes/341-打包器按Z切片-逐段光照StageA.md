# 341 · 打包器按 Z 切片（逐段光照第 1 半，Stage A）

日期：2026-09-28 · 承接：`notes/339` §7.5（为什么只能切片）→ `notes/340`（基线探针，尚未接入）

**本轮只做"把车体沿车长切成段"这一半（打包器侧）**，并把它证到"几何一个字节没变"。
mod 侧（每段各自取光）是 Stage B，尚未开始 —— 所以**现在还没有任何视觉变化**，
`sliceForLighting` 也**还没有写进任何在用车型的配置**（测试用的是 `sandbox/` 里的配置副本）。

---

## 1. ★ 关键发现：同名组会被**整个丢掉**（这条差点让模型被切碎）

反汇编 `de.javagl.obj.ObjSplitting.splitByGroups`（`Minecraft-Mappings-common-0.0.1.jar`）：

```java
for (int i = 0; i < obj.getNumGroups(); i++) {
    final ObjGroup group = obj.getGroup(i);
    if (group.getNumFaces() <= 0) continue;
    result.put(group.getName(), ObjUtils.groupToObj(obj, group, null));   // ★ 按【名字】put
}
```

它遍历**组对象**（`g`/`o` 各算一个对象）、按**名字**放进 `LinkedHashMap` ⇒
**同一个名字出现两块，前一块被整个丢掉**。这正是 `notes/191` 那句"只剩最后一小块电机"的机制。

**对本功能的后果**：`body` 组内部的面是**按源对象顺序**排的（`car_body` → `buffer_beam` → `buffers`
→ `bogies` → `wiper_motors`），Z 来回跳。若按"遇到新段就插一条 `g body_zNN`"的朴素做法，
输出里会出现**1360 条 `g body_zNN`**（实测数字）⇒ MTR 每个段名只保留最后一块 ⇒ **车体被切碎**。

⇒ 必须把**同一段的面收拢到一处**，也就是在组内**按段重排面**。
于是初版设计里"只插入/删除 `g` 行、输出逐字节不变"这条更强的性质**不再成立**
（面的顺序变了、顶点池顺序也跟着变），判据换成 **几何多重集不变**。

## 2. 实现（`pack_vehicle.js`）

新增三处：

| 位置 | 内容 |
|---|---|
| `sliceGroupsByZ(text,spec,outNames)` | ① 建顶点池；② 一次扫描定位目标组的所有块 + 收下每个面（连同它在原文件里的**生效材质**）；③ 段数 = `round(组自身 Z 跨度 / stepBlocks)`，用 `span/count` 精确铺满；④ **按段收拢**、段内材质变化处补 `usemtl`、空段不发；⑤ 整块替换回原位 |
| `canonicalFaceMultiset(text)` | 把一份 OBJ 解析成**几何多重集**：逐面解析成 `位置/UV/法线` + 生效材质，保持角的顺序（绕序有意义），排序后返回。**刻意与切片器各自独立实现** —— 让切片器用自己的工具证明自己，证明不了什么 |
| `expandSlicedParts(parts,slicedNames)` | 原 part 按段展开成 N 条（同名同设置、只换名字）：段在 MTR 的数据模型里是各自独立的 part，语义上仍是同一块车体 |

配置（新增，缺省=不切片，所以现有配置一字不变）：

```jsonc
"sliceForLighting": { "groups": ["body"], "stepBlocks": 1.0, "maxSegments": 64 }
```

**两条内置自检（不通过就抛异常，宁可打不出包）**：

1. 每个组名在输出里只出现一次（`ObjSplitting` 那个坑）；
2. `canonicalFaceMultiset(切片前) === canonicalFaceMultiset(切片后)` —— 同样的三角形 / UV / 法线 / 绕序 / 材质分配。

另外：`sliceForLighting` 指名的组若 `used` 认它却一个面都没找到 ⇒ **抛异常**（拒绝产出"分段静默没生效"的包）。

## 3. 验证（三层，全部通过）

测试配置：`sandbox/litslice/{off,on}.json`（BR101 配置的副本，只差 `sliceForLighting`）。

### 3.1 打包器自检（每次打包都跑）

```
lighting slices: body -> 18 段（body_z00 .. body_z19），每段约 1 格
lighting slices: 自检通过 —— 面数 9587 不变、几何多重集逐面一致、每个组名只出现一次
```

### 3.2 独立校验器 `sandbox/litslice-check.js`（另写一份解析器，不复用打包器任何函数）

| 判据 | 结果 |
|---|---|
| 面数 | 19500 = 19500 ✓ |
| **总面积** | **898.316287 = 898.316287**（小数点后 6 位完全相同）✓ |
| 包围盒 | `[-1.541812, 0, -9.982578] .. [1.541812, 3.972126, 9.982578]` 完全相同 ✓ |
| 组名唯一性 | 40 个名字全部只出现一次 ✓ |
| 段面数合计 | 13388 == 原 `body` 面数 13388 ✓ |
| 首尾覆盖原 body Z 跨度 | `[-9.98, 9.98]` 完全覆盖 ✓ |

### 3.3 项目既有判据 `verify_mtr_obj.js`（**已扩展**）

必须扩展它 —— 它原来按名字查源组，段名 `body_z00` 一律报"没有对应的源组"（18 条 FAIL）。
扩展内容：

- **新增 L0**：打包后的 OBJ 里每个组名只许出现一次（即 §1 那个坑，**只有它能抓住"文本里都在、MTR 只看到一半"**）；
- L2 逐段查：`<base>_zNN` 回落到源组 `<base>`（同一份几何的子集，合法）；
- **L3/L4 按 base 汇总**：单独一段当然对不上整块车体的面积与包围盒，必须把同 base 的所有段合起来比。

结果：

```
OFF  L0 groups: 23 blocks, 23 distinct     L3/L4 body: area 534.964 (source 534.964), 13388 tris in 1 block
ON   L0 groups: 40 blocks, 40 distinct     L3/L4 body: area 534.964 (source 534.964), 13388 tris in 18 blocks  ← 逐段光照切片
两者 L1 layout 同、L2 pairs 都是 58320 个角逐一对照源模型
PASS
```

## 4. 残留特性（诚实记录，都不是 bug）

1. **大面跨带**：切片按**三角化之前**的面心归段，`toMtrObj` 把四边形切成两半后，
   大面的子三角形面心会落到相邻带里 —— 实测 **321/13388 = 2.4%**。
   ⇒ 判据不能是"每个三角面心都在带内"（**在三角化后的数据上本来就不成立**），
   更不能是"段的面心区间随段号单调"（段 `z06/z07/z09/z10` 只含 4~50 个巨大面，中位数会被它们带跑）。
   这两条都只作**信息量**输出，硬判据只留面数/面积/包围盒/唯一性/段面数合计/覆盖。
   > 教训：判据必须在**判据能成立的那个数据层面**上写 —— 拿三角化后的面心去核对三角化前的归段，会得到一个永远红的守卫。
2. **空段**：计划 20 段、实得 18 段（`z08`/`z11` 那一带只有跨带的大面、没有面心落在里面）⇒ 空段直接不发（否则会多出没有几何的 part）。
3. **段的顶点 Z 范围会互相重叠** —— 正常（一个面跨过段界就是这样）；有意义的是面心归属。

## 5. 代价（用 notes/340 的基线推算）

`body` 从 1 个组变 18 个组 ⇒ 该节车的车体 draw 数按材质组数 ×18 量级增长；
按实测基线（10 节编组、车辆 draws 152、优化批次 0.31–0.40 ms）估计 **总 draws +12%~23%、优化批次 +0.04–0.08 ms**。
真正的数字要等 Stage B 接上后用 `[MMTR-DRAW]` 量。

## 6. 下一步（Stage B，mod 侧）

1. 把"节的车体位姿"从 `RenderVehicles` 下传到部件绘制（`VehicleResource.queue` → `iterateModels` → `ModelPropertiesPart.render`，约 4 处签名）；
2. **所有 EXTERIOR 部件按自身包围盒中心取光**（不是只给段取 —— 否则车头端会出现"车体很亮、旁边的门很暗"的接缝，notes/339 §7.5.3）；
3. 段部件各自独立入队（走门/机制件那条现成 wrapper 路，绕开 `PartCondition` 合并键）；
4. 细节距离：近处用段、远处退化成整节（照 NTE 的 `isInDetailDistance`）。
