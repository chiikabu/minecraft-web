// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi;

public class SystemInfo {
	public static PlatformEnum getCurrentPlatform() {
		return PlatformEnum.UNKNOWN;
	}

	public oshi.hardware.HardwareAbstractionLayer getHardware() {
		throw new UnsupportedOperationException("oshi unavailable in browser");
	}

	public oshi.software.os.OperatingSystem getOperatingSystem() {
		throw new UnsupportedOperationException("oshi unavailable in browser");
	}
}
