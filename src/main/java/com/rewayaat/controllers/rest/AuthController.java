package com.rewayaat.controllers.rest;

import com.rewayaat.core.data.UserAccount;
import com.rewayaat.service.AuthService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Authentication APIs backed by Elasticsearch.
 */
@Hidden
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Register a new account.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Registration processed."),
            @ApiResponse(responseCode = "400", description = "Invalid payload")
    })
    @RequestMapping(value = "/register", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> payload) throws Exception {
        Map<String, Object> response = authService.register(
                value(payload, "displayName"),
                value(payload, "email"),
                rawValue(payload, "password"));
        HttpStatus status = Boolean.TRUE.equals(response.get("ok")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(response, status);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Verify email token.")
    @RequestMapping(value = "/verify", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> verify(@RequestParam("token") String token) throws Exception {
        Map<String, Object> response = authService.verifyEmailToken(token);
        HttpStatus status = Boolean.TRUE.equals(response.get("ok")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(response, status);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Log in using email and password.")
    @RequestMapping(value = "/login", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> payload) throws Exception {
        Map<String, Object> response = authService.login(value(payload, "email"), rawValue(payload, "password"));
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
        }
        String rawToken = String.valueOf(response.get("token"));
        response.remove("token");
        ResponseCookie cookie = ResponseCookie.from(AuthService.AUTH_COOKIE, rawToken)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofSeconds(authService.sessionTtlSeconds()))
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        return new ResponseEntity<>(response, headers, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Log out current session.")
    @RequestMapping(value = "/logout", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken) throws Exception {
        authService.logout(sessionToken);
        ResponseCookie cookie = ResponseCookie.from(AuthService.AUTH_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        return new ResponseEntity<>(response, headers, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns the current authenticated user.")
    @RequestMapping(value = "/me", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> me(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        Map<String, Object> response = new HashMap<>();
        if (user == null) {
            response.put("authenticated", false);
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        response.put("authenticated", true);
        response.put("user", authService.publicUser(user));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Start password reset flow.")
    @RequestMapping(value = "/reset/request", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> requestReset(@RequestBody Map<String, String> payload) throws Exception {
        return new ResponseEntity<>(authService.requestPasswordReset(value(payload, "email")), HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Confirm password reset with token.")
    @RequestMapping(value = "/reset/confirm", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> confirmReset(@RequestBody Map<String, String> payload) throws Exception {
        Map<String, Object> response = authService.confirmPasswordReset(
                value(payload, "token"),
                rawValue(payload, "password"));
        HttpStatus status = Boolean.TRUE.equals(response.get("ok")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(response, status);
    }

    private String value(Map<String, String> payload, String key) {
        if (payload == null) {
            return "";
        }
        String value = payload.get(key);
        return value == null ? "" : value.trim();
    }

    private String rawValue(Map<String, String> payload, String key) {
        if (payload == null) {
            return "";
        }
        String value = payload.get(key);
        return value == null ? "" : value;
    }
}
