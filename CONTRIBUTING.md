# Contributing to mobileKinetic:Agent

Thank you for your interest in contributing to mK:a!

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a feature branch: `git checkout -b feature/my-feature`
4. Make your changes
5. Build and test: `./gradlew assembleDebug`
6. Commit your changes with a descriptive message
7. Push to your fork and submit a pull request

## Development Setup

- **Android Studio**: Latest stable release
- **JDK**: 21 (bundled with Android Studio)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36

## Code Style

- Kotlin files follow standard Kotlin conventions
- Java files (terminal modules) follow standard Java conventions
- Use meaningful variable and function names
- Keep functions focused and concise

## Architecture

mK:a uses a modular architecture:

- **app**: Main application module (Kotlin, Jetpack Compose, Hilt)
- **shared**: Shared constants and utilities
- **terminal-emulator**: Terminal emulation engine (Java, JNI)
- **terminal-view**: Terminal rendering view (Java)

### Key Patterns

- **Pluggable AI Providers**: Implement `AiProvider` interface for new AI backends
- **Pluggable TTS Providers**: Implement `TtsProvider` interface for new TTS engines
- **Room Database**: Always use proper migrations (`ALTER TABLE`), never destructive migration
- **Navigation**: Uses boolean state flags in composables, not NavController

## Pull Request Guidelines

- Keep PRs focused on a single feature or fix
- Include a clear description of what changed and why
- Ensure the build passes (`./gradlew assembleDebug`)
- Update documentation if your change affects public APIs or user-facing behavior

## Reporting Issues

- Use GitHub Issues for bug reports and feature requests
- Include device info, Android version, and reproduction steps for bugs
- Search existing issues before creating a new one

## License

By contributing, you agree that your contributions will be licensed under the GPLv3 license.
