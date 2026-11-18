// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services.response;

import java.util.Map;

public record ErrorResponse(String path, String error, String errorMessage, Map<String, Object> details) {
}
