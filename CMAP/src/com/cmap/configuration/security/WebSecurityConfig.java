package com.cmap.configuration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.BeanIds;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import com.cmap.configuration.filter.RequestBodyReaderAuthenticationFilter;
import com.cmap.security.AuthSuccessHandler;
import com.cmap.security.AuthUnsuccessHandler;
import com.cmap.security.UserDetailsServiceImpl;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

	@Bean(name = BeanIds.AUTHENTICATION_MANAGER)
	@Override
	public AuthenticationManager authenticationManagerBean() throws Exception {
		return super.authenticationManagerBean();
	}

	@Bean
	public AuthSuccessHandler authSuccessHandler() {
		return new AuthSuccessHandler();
	};

	@Bean
	public AuthUnsuccessHandler authUnsuccessHandler() {
		return new AuthUnsuccessHandler();
	};

	@Override
	@Bean
	public UserDetailsService userDetailsService() {
		return new UserDetailsServiceImpl();
	};

	@Bean
	public BCryptPasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	};

	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		UserDetailsService userDetailsService = userDetailsService();
		auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
	}

	@Bean
    public RequestBodyReaderAuthenticationFilter authenticationFilter() throws Exception {
        RequestBodyReaderAuthenticationFilter authenticationFilter
            = new RequestBodyReaderAuthenticationFilter();
        authenticationFilter.setAuthenticationSuccessHandler(authSuccessHandler());
        authenticationFilter.setAuthenticationFailureHandler(authUnsuccessHandler());
//        authenticationFilter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login"));
        authenticationFilter.setRequiresAuthenticationRequestMatcher(new AntPathRequestMatcher("/login", "POST"));
        authenticationFilter.setAuthenticationManager(authenticationManagerBean());
        return authenticationFilter;
    }

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http.authorizeRequests()
			.antMatchers("/resources/**").permitAll()
			.antMatchers("/login/code/**").permitAll()
			.antMatchers("/login/authByOIDC/**").permitAll()
			.antMatchers("/admin/env/refreshAll").permitAll()
			.anyRequest().hasAnyRole("ADMIN", "USER")
			.and()
			/*
			.addFilterBefore(authenticationFilter(),
	                UsernamePasswordAuthenticationFilter.class)
	                */
		.formLogin().loginPage("/login").permitAll()
//			.successHandler(authSuccessHandler())
//			.failureHandler(authUnsuccessHandler())
			.and()
		.logout()
	    .permitAll()
	    	.and()
	    	.headers()
			//.contentSecurityPolicy("default-src 'self'")	//http://www.ruanyifeng.com/blog/2016/09/csp.html
			//.and()
			.frameOptions()
			.disable()
			.addHeaderWriter(new StaticHeadersWriter("X-FRAME-OPTIONS", "ALLOW-FROM https://163.19.163.170"))
			.addHeaderWriter(new StaticHeadersWriter("X-FRAME-OPTIONS", "ALLOW-FROM https://163.19.163.170:1443"))
			.and()
		.csrf().disable();
	}
}
