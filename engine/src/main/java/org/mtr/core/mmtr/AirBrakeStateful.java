package org.mtr.core.mmtr;

/**
 * 带气制动状态（列车管压 / 制动缸压）的控制器。
 *
 * <p>存在的理由只有一个：**镜像种子**。服务端权威控制器每拍算出的管压/缸压要发给客户端，
 * 客户端重建自己的控制器时要把这份状态灌回去（否则两端从不同的缸压起步，渲染速度会漂）。
 * 以前这段用 {@code instanceof AirBrakeController} 写死，三手柄控制器（同样带气制动状态）一接进来就会漏 ——
 * 所以抽成接口，由 {@link Vehicle} 按能力而不是按具体类型判断。</p>
 */
public interface AirBrakeStateful {

	double getPipePressure();

	double getBrakeCylinderPressure();

	/** 用权威快照里的管压/缸压给新控制器播种。 */
	void setState(double pipePressure, double brakeCylinderPressure);
}
