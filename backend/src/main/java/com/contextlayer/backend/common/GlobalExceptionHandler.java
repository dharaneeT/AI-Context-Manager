package com.contextlayer.backend.common;

import com.contextlayer.backend.agent.AgentServiceException;
import com.contextlayer.backend.embedding.EmbeddingException;
import com.contextlayer.backend.git.GitException;
import com.contextlayer.backend.llm.LlmException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
		return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fields);
	}

	private ResponseEntity<ApiError> build(
		HttpStatus status,
		String message,
		HttpServletRequest request,
		Map<String, String> fieldErrors
	) {
		ApiError body = new ApiError(
			status.value(),
			status.getReasonPhrase(),
			message,
			request.getRequestURI(),
			Instant.now().toString(),
			fieldErrors
		);
		return ResponseEntity.status(status).body(body);
	}

	@ExceptionHandler(GitException.class)
	public ResponseEntity<ApiError> handleGit(GitException ex, HttpServletRequest request) {
		return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
	}

	@ExceptionHandler(EmbeddingException.class)
	public ResponseEntity<ApiError> handleEmbedding(EmbeddingException ex, HttpServletRequest request) {
		return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, null);
	}

	@ExceptionHandler(LlmException.class)
	public ResponseEntity<ApiError> handleLlm(LlmException ex, HttpServletRequest request) {
		return build(
			ex.isNotConfigured() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
			ex.getMessage(),
			request,
			null
		);
	}

	@ExceptionHandler(AgentServiceException.class)
	public ResponseEntity<ApiError> handleAgent(AgentServiceException ex, HttpServletRequest request) {
		return build(
			ex.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
			ex.getMessage(),
			request,
			null
		);
	}

	/** The database went away (stopped container, restart, sleep). Say so instead of a bare 500. */
	@ExceptionHandler(
		{
			CannotCreateTransactionException.class,
			CannotGetJdbcConnectionException.class,
			DataAccessResourceFailureException.class
		}
	)
	public ResponseEntity<ApiError> handleDatabaseDown(Exception ex, HttpServletRequest request) {
		return build(
			HttpStatus.SERVICE_UNAVAILABLE,
			"The database is not reachable right now. Check that the contextlayer-db container is running.",
			request,
			null
		);
	}
}
