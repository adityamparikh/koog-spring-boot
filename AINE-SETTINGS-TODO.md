# GitHub settings still to apply (AINE readiness)

Some readiness items can only be satisfied by repository settings on GitHub, not by files in the
tree. Nothing below has been applied: the `aine-readiness` branch was prepared without pushing or
calling any GitHub write API. Run these as the repo owner, **after** `.github/workflows/ci.yml`
has reached `main` and run at least once (a required check must have reported before GitHub can
match it).

Current state, read on 2026-11-20 with `gh api` (GET only):

| Setting | State |
|---------|-------|
| Branch protection on `main` | none (`Branch not protected`), no rulesets |
| Actions | enabled, all actions allowed, default `GITHUB_TOKEN` read-only |
| Secret scanning / push protection | enabled / enabled |
| Dependabot alerts / security updates | enabled / enabled |
| Visibility | public |

## 1. Protect `main` and require the CI check

Unblocks the CI item (a change cannot land without `build` passing), and puts a pull request,
read by the owner, in front of every change to `main` (the review-rules and human-gate items).
`build` is the job name in `.github/workflows/ci.yml`. Approvals are set to 0 because this is a
solo repository and GitHub does not let an author approve their own PR; raise it when a second
maintainer joins.

```bash
gh api -X PUT repos/adityamparikh/koog-spring-boot/branches/main/protection \
  -H "Accept: application/vnd.github+json" --input - <<'JSON'
{
  "required_status_checks": { "strict": true, "contexts": ["build"] },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": true
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
```

Verify:

```bash
gh api repos/adityamparikh/koog-spring-boot/branches/main/protection \
  --jq '{checks: .required_status_checks.contexts, reviews: .required_pull_request_reviews.required_approving_review_count, admins: .enforce_admins.enabled, force_push: .allow_force_pushes.enabled}'
```

## 2. Optional: catch generic secrets, not just provider keys

Push protection already blocks known provider key formats. Non-provider patterns add generic
secrets (private keys, connection strings) to the scan. Public repositories get this at no cost.

```bash
gh api -X PATCH repos/adityamparikh/koog-spring-boot \
  -H "Accept: application/vnd.github+json" \
  -f 'security_and_analysis[secret_scanning_non_provider_patterns][status]=enabled'
```

## Not needed

- **Deployment environments with required reviewers:** there is no deployment target (local
  tutorial build, `feature.md` § Out of Scope: production hardening), so there is nothing to gate.
- **Dependabot alerts:** already on. `.github/workflows/dependency-submission.yml` makes them
  cover the BOM-managed dependencies once it has run on `main`.
