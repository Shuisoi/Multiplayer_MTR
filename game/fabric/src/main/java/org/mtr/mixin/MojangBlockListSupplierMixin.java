package org.mtr.mixin;

import com.mojang.patchy.MojangBlockListSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * **登录卡死守卫：不让"拉黑名单"把客户端钉在类初始化里**（2026-09-21 实机事故）。
 *
 * <h2>现场</h2>
 *
 * <p>用户报"连不上服务器"（notes/230 §6 那一次重启之后）。服务器侧一切正常：
 * 25565 在监听、8888 应答、`online-mode=false` + `enforce-secure-profile=false`，
 * 从本机 `127.0.0.1:25565` 测试连接也通。而客户端日志停在
 * {@code Connecting to 127.0.0.1, 25565} 之后再无输出，线程转储是决定性的：</p>
 *
 * <pre>
 * "Server Connector #1" ... waiting on the Class initialization monitor for
 *     net.minecraft.client.network.AllowedAddressResolver
 *   at com.mojang.patchy.MojangBlockListSupplier.createBlockList(MojangBlockListSupplier.java:23)
 *   at net.minecraft.client.network.BlockListChecker.create(BlockListChecker.java:20)
 *   at sun.security.ssl.SSLSocketImpl.startHandshake(...)   ← 卡在这里
 * </pre>
 *
 * <p>链条是：{@code ConnectScreen} 要用 {@code AllowedAddressResolver} → 它的**静态初始化**
 * 建 {@code BlockListChecker} → patchy 去抓
 * {@code https://sessionserver.mojang.com/blockedservers} → 本机到 Mojang 的 TLS 握手
 * 一直不返回 → **类初始化永远不完成** → 于是"连接中"永远转圈，**连超时都没有**。
 * 修前实测：3 条到 Microsoft 边缘（443）的连接挂着，整个渲染/登录线程池都在等那个类初始化锁。</p>
 *
 * <h2>为什么不是"网络问题，重试就好"</h2>
 *
 * <p>{@code MojangBlockListSupplier} 里那次 {@code getInputStream()} **没有读超时**
 * （javap 出来的字节码：硬编码的 URL → {@code openConnection()} → {@code getInputStream()}）。
 * 只要这条路由不通，客户端就**必然**挂在同一个地方 —— 这不是抖动，是"没有超时的网络调用
 * 长在类初始化里"这个结构问题。开发/离线环境里黑名单本来也没有意义，所以这里直接**不抓**。</p>
 *
 * <h2>开关</h2>
 *
 * <p>默认不抓（空黑名单）。想恢复原版行为（有外网时）加 JVM 参数
 * {@code -Dmmtr.blocklistFetch=true} —— 那一次抓取就照旧发生，一点不拦。</p>
 *
 * <h2>两条**必须**这么写的东西（都踩过）</h2>
 *
 * <ol>
 *   <li><b>{@code remap = false}</b>：这是混进**库类**（不是 Minecraft 类）的关键。
 *       少了它，mixin 注解处理器会报
 *       {@code Unable to locate obfuscation mapping for @Inject target createBlockList} ——
 *       因为它在 Minecraft 的混淆表里找不到 patchy 的成员，于是**整个客户端编译不过**
 *       （2026-09-21 实机：IDEA 拉不起来客户端，就是这个）。库类的名字本来就不该被混淆表改写，
 *       所以正解是显式声明"这一条不要重映射"。</li>
 *   <li><b>单独的 mixin 配置</b>（{@code mtr.library.mixins.json}，**不写 refmap**）：
 *       主配置 {@code mtr.mixins.json} 带 {@code refmap}，那是给 Minecraft 类用的；
 *       库类目标放进一个没有 refmap 的配置里，运行期也不会去找不存在的映射。</li>
 * </ol>
 */
@Mixin(MojangBlockListSupplier.class)
public abstract class MojangBlockListSupplierMixin {

	@Inject(method = "createBlockList()Ljava/util/function/Predicate;", at = @At("HEAD"), cancellable = true, remap = false)
	private void mmtrSkipBlockListFetch(CallbackInfoReturnable<Predicate<String>> callbackInfo) {
		if (Boolean.getBoolean("mmtr.blocklistFetch")) {
			org.mtr.mod.Init.LOGGER.info("[MMTR-NET] 封禁服务器名单：按 -Dmmtr.blocklistFetch=true 照原版抓取（有外网时才该这么做）");
			return;
		}
		// 自白一行：这条守卫生效时**必须**能在日志里看见，否则"连不上"时又得靠线程转储去猜
		org.mtr.mod.Init.LOGGER.info("[MMTR-NET] 已跳过封禁服务器名单抓取（离线守卫，notes/231：那次抓取没有超时且长在类初始化里，会让登录永久卡住）");
		callbackInfo.setReturnValue(serverAddress -> false);
	}
}
