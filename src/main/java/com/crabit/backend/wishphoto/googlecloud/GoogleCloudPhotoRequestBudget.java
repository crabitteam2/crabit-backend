package com.crabit.backend.wishphoto.googlecloud;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A request-owned deadline: never retained on a pooled worker thread or reset per photo. */
public final class GoogleCloudPhotoRequestBudget extends OncePerRequestFilter implements Ordered {
	static final Duration RPC_TIMEOUT = Duration.ofSeconds(2);
	private static final String ATTRIBUTE = GoogleCloudPhotoRequestBudget.class.getName() + ".deadline";
	private final LongSupplier nanoTime;

	public GoogleCloudPhotoRequestBudget() { this(System::nanoTime); }
	public GoogleCloudPhotoRequestBudget(LongSupplier nanoTime) { this.nanoTime = nanoTime; }
	@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

	@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		if (request.getAttribute(ATTRIBUTE) == null) {
			String path = request.getRequestURI().substring(request.getContextPath().length());
			boolean upload = "POST".equals(request.getMethod()) && "/v1/wish-photos".equals(path);
			request.setAttribute(ATTRIBUTE, new Deadline(nanoTime, Duration.ofSeconds(upload ? 28 : 8)));
		}
		chain.doFilter(request, response);
	}

	static Deadline signingDeadline() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request == null) return new Deadline(System::nanoTime, Duration.ofSeconds(8));
		// Also supports direct MVC dispatches that do not use the servlet filter registration.
		Object existing = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (existing instanceof Deadline deadline) return deadline;
		Deadline deadline = new Deadline(System::nanoTime, Duration.ofSeconds(8));
		request.setAttribute(ATTRIBUTE, deadline, RequestAttributes.SCOPE_REQUEST);
		return deadline;
	}

	static final class Deadline {
		private final LongSupplier nanoTime;
		private final long expiresAt;
		Deadline(LongSupplier nanoTime, Duration budget) {
			this.nanoTime = nanoTime;
			this.expiresAt = nanoTime.getAsLong() + budget.toNanos();
		}
		void requireRpcBudget() {
			// A started RPC may consume its entire configured timeout, not just its start instant.
			if (expiresAt - nanoTime.getAsLong() < RPC_TIMEOUT.toNanos()) throw new IllegalStateException();
		}
		void requireRemaining() {
			if (expiresAt - nanoTime.getAsLong() <= 0) throw new IllegalStateException();
		}
	}
}
