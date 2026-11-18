// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib;

import java.util.UUID;

public interface ProfileLookupCallback {
	void onProfileLookupSucceeded(String name, UUID id);

	void onProfileLookupFailed(String name, Exception exception);
}
