// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

import java.util.List;

public interface HardwareAbstractionLayer {
	CentralProcessor getProcessor();
	GlobalMemory getMemory();
	List<Object> getGraphicsCards();
}
