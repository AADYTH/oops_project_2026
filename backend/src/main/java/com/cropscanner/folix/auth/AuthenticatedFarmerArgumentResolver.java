package com.cropscanner.folix.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets any controller method simply declare an {@link AuthenticatedFarmer} parameter. If there is no
 * authenticated farmer on the request it throws {@link FolixAuthException#unauthenticated()} — the
 * method fails closed even if someone forgets to put its path under {@link FolixWebConfig#PROTECTED_PATHS}.
 */
public class AuthenticatedFarmerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedFarmer.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw FolixAuthException.unauthenticated();
        }
        return FolixAuthInterceptor.currentFarmer(request).orElseThrow(FolixAuthException::unauthenticated);
    }
}
