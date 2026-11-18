// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.exceptions;

public class MinecraftClientException extends RuntimeException {
	public enum ErrorType {
		SERVICE_UNAVAILABLE,
		HTTP_ERROR,
		JSON_ERROR
	}

	protected final ErrorType type;

	protected MinecraftClientException(ErrorType type, String message) {
		super(message);
		this.type = type;
	}

	public MinecraftClientException(ErrorType type, String message, Throwable cause) {
		super(message, cause);
		this.type = type;
	}

	public ErrorType getType() {
		return type;
	}

	public AuthenticationException toAuthenticationException() {
		return new AuthenticationException(getMessage(), this);
	}
}
