# Contributing

## Thank You

Thank you for your interest in contributing to Minecraft Nodes! This guide will help you get started.

## Requirements

- A GitHub account
- Git installed on your local machine
- A Java IDE or text editor
- A basic understanding of Kotlin

## Getting Started

1. [Sign up for GitHub](https://github.com/signup) if you don't have an account.
2. Fork the repository by clicking **Fork** at the top right of the repo page.
3. Clone your fork: `git clone https://github.com/<your-username>/minecraft-nodes.git`
4. Open the `nodes/` project in your IDE.
5. Build the plugin:
   ```
   cd nodes
   ./gradlew build
   ```
   If you encounter errors, please open an issue.

## Identifying What to Work On

### Issues

Work items are tracked as [GitHub issues](https://github.com/ginjerul/ginjer-nodes/issues).

## Making Changes

1. Make sure an issue exists for the work. If not, create one.
2. Switch to `develop`: `git checkout develop`
3. Create a branch: `git checkout -b <branch-name>`
4. Make your changes.
5. Test your changes (see [Testing](#testing)).
6. Commit: `git commit -m "Description of changes"`
7. Push: `git push origin <branch-name>`
8. Open a pull request against `develop`, linking the related issue with `#<number>`.
9. Address review feedback.

## Testing

Run the unit tests with:

Linux:
```
cd nodes
./gradlew clean test
```

Windows:
```
cd nodes
.\gradlew.bat clean test
```

For manual testing, build the plugin and drop it into a local Paper/Spigot server together with the [Kotlin runtime plugin](https://github.com/d-z4/minecraft-kotlin).

## Questions

Add me on discord: mententen
