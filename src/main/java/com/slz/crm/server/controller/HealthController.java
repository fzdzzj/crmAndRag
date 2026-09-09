package com.slz.crm.server.controller;
import com.slz.crm.common.enumeration.PermissionOperates;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
@Slf4j
public class HealthController {
    @GetMapping
    public String health() {
        log.info("health check");
        return "ok";
    }
}
