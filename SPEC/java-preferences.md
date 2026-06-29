# Java Preferences & Styling Guide

## Build & Tooling

- Use **Maven** as the build system
- Group ID: `co.kuznetsov`, artifact ID: lowercase, dash-delimited (e.g. `media-optimizer`)
- Always add **Checkstyle** and **SpotBugs** to the build at initial project setup
- Use a **stable version of Spring** compatible with Java 21

## Code Style

- All code lives in subpackages of `co.kuznetsov.<application-name>`
- **Never use Lombok**
- **Never use `volatile`** — use `java.util.concurrent.atomic.*` instead
- Use **SLF4J** for logging

## Frameworks

- **Spring Boot** for microservices
- For web frameworks: prefer stability, proven browser compatibility, and server-side processing
  - Minimize maintenance overhead — avoid frameworks with frequent breaking changes
  - Lean towards mature, battle-tested options

## Dependency & Security

- Keep dependencies minimal and stable
- Prefer well-maintained libraries with long support windows
