package org.mtr.mod.sound;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.MovingSoundInstanceExtension;

/**
 * 一条**随车移动的循环音**（MMTR 牵引/走行音的一档）。
 *
 * <h2>★ 距离衰减：坐在操纵位上时整级旁路（2026-10-08）</h2>
 *
 * <p>MC 对每条声音按"声源位置 ↔ 听者位置"做距离衰减（委托给 OpenAL，见
 * {@code SoundSystem.play} → {@code Source.setAttenuation}：距离模型 {@code AL_INVERSE_DISTANCE_CLAMPED}、
 * 参考距离 0、滚降 1、最大距离 = {@code max(音量,1) × sounds.json 的 attenuation_distance}）。
 * 对我们的车来说这条衰减是**对的**：站在站台上听别人的车，本来就该近响远轻。</p>
 *
 * <p>但它对**司机自己**是错的：人已经坐在驾驶室里，声源却是"这节车的中心"（离驾驶室端约 8–9.75 m），
 * 于是**自己的车声被当成远处的车声**削一遍。用户口径：<b>人在驾驶位上就应当听满音量的音频、忽略距离</b>。</p>
 *
 * <p>实现取的是**规范保证**的那条路，而不是去凑那条曲线：把
 * {@code getAttenuationType()} 报成 {@code NONE} ⇒ MC 调 {@code Source.disableAttenuation()}
 * ⇒ {@code AL_DISTANCE_MODEL = AL_NONE} ⇒ <b>增益恒为 1</b>，任何 OpenAL 实现都一样
 * （逆距离式在参考距离 0 处会退化，曲线是实现相关的，我们不去依赖它 —— 直接整级关掉）。
 * 位置仍然照常设置，所以左右/前后**声像保留**，丢掉的只有距离滚降。</p>
 *
 * <p>⚠️ 衰减口径是**在 play() 那一刻**写给 OpenAL 的，之后每 tick 只更新音量与位置
 * （{@code SoundSystem.play} 里设衰减，{@code SoundSystem.tick} 里只设 volume/pitch/position）。
 * 所以口径一变**必须重新起播**——见 {@link #setData} 里那段。不重启的话，
 * 人从车厢走进驾驶室那一刻听到的还是老口径。</p>
 */
public class VehicleLoopingSoundInstance extends MovingSoundInstanceExtension {

	/** 要报给 MC 的衰减口径：true = 忽略距离（人就在这节车的操纵位上）。 */
	private boolean noAttenuation;
	/** 上一次**真正起播时**用掉的口径 —— 与 {@link #noAttenuation} 不一致就说明要重启一次。 */
	private boolean appliedNoAttenuation;

	public VehicleLoopingSoundInstance(SoundEvent event) {
		super(event, SoundCategory.getBlocksMapped());
		setIsRepeatableMapped(true);
		setRepeatDelay(0);
		setVolume(0);
		setPitch(1);
	}

	/**
	 * 这个声源要不要忽略距离衰减。
	 *
	 * <p>由 {@link MmtrVehicleSound#setListenerAtControls} 逐条推下来；
	 * 口径变了不用调用方操心重启，{@link #setData} 下一帧会自己做。</p>
	 */
	public void setNoAttenuation(boolean noAttenuation) {
		this.noAttenuation = noAttenuation;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>★ 这条 override 是整项功能的落点：{@code NONE} ⇒ MC 不设距离模型 ⇒ OpenAL 增益恒 1。
	 * 用全限定名是因为本包已经 import 了 {@code org.mtr.mapping.holder.SoundInstance}（同名不同物）。</p>
	 */
	@Override
	public net.minecraft.client.sound.SoundInstance.AttenuationType getAttenuationType() {
		return noAttenuation
				? net.minecraft.client.sound.SoundInstance.AttenuationType.NONE
				: net.minecraft.client.sound.SoundInstance.AttenuationType.LINEAR;
	}

	public void setData(float volume, float pitch, BlockPos blockPos) {
		setPitch(pitch == 0 ? 1 : pitch);
		setVolume(volume);
		setX(blockPos.getX());
		setY(blockPos.getY());
		setZ(blockPos.getZ());

		final SoundManager soundManager = MinecraftClient.getInstance().getSoundManager();

		/*
		 * ★ 衰减口径变了 ⇒ 先停掉，让下面那一支用新口径重新起播。
		 * 为什么必须重启：MC 只在 play() 那一刻把距离模型写给 OpenAL（见类注释），
		 * 之后改 getAttenuationType() 的返回值对已经响着的那条源**没有任何影响**。
		 */
		if (soundManager.isPlaying(new SoundInstance(this)) && appliedNoAttenuation != noAttenuation) {
			soundManager.stop(new SoundInstance(this));
		}

		if (soundManager.isPlaying(new SoundInstance(this))) {
			if (volume <= 0) {
				soundManager.stop(new SoundInstance(this));
			}
		} else {
			if (volume > 0) {
				// 记下"这条源是用哪个口径起播的"，它就是下一次判断要不要重启的依据
				appliedNoAttenuation = noAttenuation;
				setIsRepeatableMapped(true);
				soundManager.play(new SoundInstance(this));
			}
		}
	}

	@Override
	public void tick2() {
	}

	@Override
	public boolean shouldAlwaysPlay2() {
		return true;
	}

	@Override
	public boolean canPlay2() {
		return true;
	}

	public void dispose() {
		setDone2();
		final SoundManager soundManager = MinecraftClient.getInstance().getSoundManager();
		soundManager.stop(new SoundInstance(this));
	}
}
