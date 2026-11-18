// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.software.os;

public interface OperatingSystem {
	String getFamily();
	String getVersionInfo();
	OSProcess getCurrentProcess();
	boolean isElevated();
}
