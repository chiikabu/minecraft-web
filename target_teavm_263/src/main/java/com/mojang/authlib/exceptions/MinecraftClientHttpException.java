// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.exceptions;

import java.time.Duration;
import java.util.Optional;

import com.mojang.authlib.services.response.ErrorResponse;

public class MinecraftClientHttpException extends MinecraftClientException {
	public static final int UNAUTHORIZED = 401;
	public static final int FORBIDDEN = 403;

	private final int status;
	private final ErrorResponse response;
	private final Duration retryAfter;

	public MinecraftClientHttpException(int status) {
		this(status, null, null);
	}

	public MinecraftClientHttpException(int status, ErrorResponse response) {
		this(status, response, null);
	}

	public MinecraftClientHttpException(int status, ErrorResponse response, Duration retryAfter) {
		super(ErrorType.HTTP_ERROR, "HTTP error " + status);
		this.status = status;
		this.response = response;
		this.retryAfter = retryAfter;
	}

	public int getStatus() {
		return status;
	}

	public Optional<ErrorResponse> getResponse() {
		return Optional.ofNullable(response);
	}

	public Optional<Duration> getRetryAfter() {
		return Optional.ofNullable(retryAfter);
	}
}
