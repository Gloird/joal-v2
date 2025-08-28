package org.araymond.joal.web.config;

import org.araymond.joal.web.annotations.ConditionalOnWebUi;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.*;

@ConditionalOnWebUi
// Do not use @EnableWebMvc as it will remove all the default springboot config.
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {
    private final String[] RESOURCE_LOCATIONS = new String[]{"classpath:/public/"};
    private final String[] RESOURCE_LOCATIONS2 = new String[]{"classpath:/public/assets/"};

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**")
                    .addResourceLocations(RESOURCE_LOCATIONS);
            registry.addResourceHandler("/assets/**")
                    .addResourceLocations(RESOURCE_LOCATIONS2);
    }

    @Override
    public void addViewControllers(final ViewControllerRegistry registry) {
        // The webui passes the credentials alongs with ui call, redirect them as well
        //registry.addRedirectViewController("/", "/").setKeepQueryParams(true);
        registry.addViewController("/").setViewName("forward:index.html");
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        //super.addViewControllers(registry);
    }
}
