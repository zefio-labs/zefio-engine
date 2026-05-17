# Contributing to Zefio Labs

First off, thank you for considering contributing to Zefio! It's people like you that make Zefio such a great tool for the community. 

We welcome contributions of all kinds: bug reports, feature requests, documentation improvements, and code patches.

## 🚀 How to Contribute

### 1. Reporting Bugs
If you find a bug, please open an issue in the repository. Provide as much information as possible:
* A clear and descriptive title.
* Steps to reproduce the issue.
* Expected behavior vs. actual behavior.
* Your environment (OS, Java/Node.js version, Zefio version).

### 2. Suggesting Enhancements
Have an idea for a new feature, a new SEDA stage plugin, or a better AI prompt routing strategy?
* Open an issue using the `enhancement` label.
* Describe the feature, why it's needed, and how it should work.

### 3. Submitting Pull Requests (PRs)
Ready to write some code? Great! Here is the workflow:

1. **Fork the repository** to your own GitHub account.
2. **Clone your fork** to your local machine.
3. **Create a new branch** for your feature or bugfix:
   ```bash
   git checkout -b feature/your-awesome-feature
   # or
   git checkout -b bugfix/issue-number
   ```
4. **Make your changes**. Ensure your code follows the existing style and architecture.
5. **Commit your changes** using Conventional Commits.
   ```bash
   git commit -m "feat: add new MQTT edge adapter"
   git commit -m "fix: resolve memory leak in SEDA queue"
   git commit -m "docs: update API routing guide"
   ```
6. **Push to your fork**:
   ```bash
   git push origin feature/your-awesome-feature
   ```
7. **Open a Pull Request** from your fork to the `main` branch of the original Zefio repository.

## 🛠 Development Setup

* **Zefio Engine (Data Plane):** Requires Java 8 or 21+. We recommend IntelliJ IDEA. Build with Maven/Gradle.
* **Zefio Console (Control Plane):** Requires Node.js (v18+) and `pnpm`. Run `pnpm install` and `pnpm dev`.

*(If you are working on features that require both, ensure the Engine is running on port 52001 before starting the Console).*

## 🧑‍💻 Code of Conduct
By participating in this project, you agree to maintain a respectful and welcoming environment for everyone. Harassment or abusive behavior will not be tolerated.

Thank you for helping us build the next generation of Event Mesh architecture!
