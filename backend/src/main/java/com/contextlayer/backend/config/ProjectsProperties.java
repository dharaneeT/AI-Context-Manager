package com.contextlayer.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "contextlayer.projects")
public record ProjectsProperties(@DefaultValue List<String> allowedRoots) {}
