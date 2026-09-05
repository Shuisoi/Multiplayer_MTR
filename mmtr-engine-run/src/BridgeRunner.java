import org.mtr.mod.bridge.EngineBridge;
import org.mtr.mod.bridge.EngineConfig;
import org.mtr.mod.bridge.NetworkEngineBridge;

public class BridgeRunner {
	public static void main(String[] args) throws Exception {
		final EngineBridge bridge = new NetworkEngineBridge();
		final boolean started = bridge.start(new EngineConfig("127.0.0.1", 8899, "/mmtr/api/bridge", 5000, "0"));
		System.out.println("started=" + started + " connected=" + bridge.isConnected());
		final long nonce = System.currentTimeMillis() % 100000;
		final boolean sent = bridge.echo("bridge-runner", nonce, data ->
			System.out.println("echoOk=" + (data != null && data.getAsJsonObject("echo") != null && data.getAsJsonObject("echo").get("nonce").getAsLong() == nonce)
				+ " payload=" + data));
		System.out.println("echoSent=" + sent);
		final var vehicles = bridge.fetchVehicles("0");
		System.out.println("vehicles=" + (vehicles == null ? "null" : vehicles.toString()));
		bridge.stop();
	}
}
