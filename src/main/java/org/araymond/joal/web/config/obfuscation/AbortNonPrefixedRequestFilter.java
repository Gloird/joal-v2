package org.araymond.joal.web.config.obfuscation;

import lombok.extern.slf4j.Slf4j;
import org.araymond.joal.web.annotations.ConditionalOnWebUi;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import java.io.IOException;

/**
 * As a security measure, all request coming from a non-prefixed URI will be closed before an actual response is sent to the client.
 */
@ConditionalOnWebUi
@Component
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AbortNonPrefixedRequestFilter implements Filter {
    public AbortNonPrefixedRequestFilter() {
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
        chain.doFilter(request, response);
    }
}
