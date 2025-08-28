package org.araymond.joal.web.config.obfuscation;

import org.araymond.joal.web.annotations.ConditionalOnWebUi;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Created by raymo on 25/07/2017.
 */
@ConditionalOnWebUi
@Configuration
public class EndpointObfuscatorConfiguration {

    public EndpointObfuscatorConfiguration() {
    }

    @Bean
    public ServletRegistrationBean<DispatcherServlet> obfuscatedDispatcherServlet() {
        final DispatcherServlet dispatcherServlet = new DispatcherServlet();

        final ApplicationContext applicationContext = new AnnotationConfigWebApplicationContext();
        dispatcherServlet.setApplicationContext(applicationContext);

        final ServletRegistrationBean<DispatcherServlet> servletRegistrationBean = new ServletRegistrationBean<>(dispatcherServlet, "/*");
        servletRegistrationBean.setName("joal");
        return servletRegistrationBean;
    }
}
