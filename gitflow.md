# Gitflow and CI

## Gitflow branching

```text
            ┌──────────────┐
            │   main       │
            └─────┬────────┘
                  │
         (tag v1.0│release)
                  │
          ┌───────┴────────┐
          │                │
   ┌──────▼──────┐   ┌─────▼──────┐
   │  develop    │   │ hotfix/*   │
   └────┬────────┘   └────┬───────┘
        │                 │
  ┌─────▼─────┐     ┌─────▼─────┐
  │feature/*  │     │release/*  │
  └───────────┘     └───────────┘
```

Long-lived branches:

- **`main`** (or `master`) — always contains production-ready code.
- **`develop`** — integration branch for features; contains the latest tested but not yet released code.

Short-lived branches:

| Branch      | Created from | Merged into          | Purpose                                      |
| ----------- | ------------ | -------------------- | -------------------------------------------- |
| `feature/*` | `develop`    | `develop`            | Building individual features or fixes.       |
| `release/*` | `develop`    | `main` and `develop` | Final QA, version bumping, and release prep. |
| `hotfix/*`  | `main`       | `main` and `develop` | Urgent fixes in production.                  |

## GitHub Actions

Create a workflow file:

```sh
mkdir -p .github/workflows && touch .github/workflows/build.yml
```

`actions/checkout` is already reasonably optimized by default: it performs a shallow clone with `depth=1`, meaning git checks out only the current `HEAD` commit and its contents. It cannot go to a different commit in history.

Useful marketplace actions:

- [HashiCorp: setup Terraform](https://github.com/marketplace/actions/hashicorp-setup-terraform)
- [Docker login](https://github.com/marketplace/actions/docker-login)
- [Azure login](https://github.com/marketplace/actions/azure-login)
- [GitHub Action for flyctl](https://github.com/marketplace/actions/github-action-for-flyctl)
