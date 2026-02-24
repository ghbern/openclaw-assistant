# QA Acceptance (Fast Loop)

Use this after each candidate build. Answer only these 3 checks.

## 1) Launch stability
- Open app -> stays foreground for 30s (Y/N)

## 2) Connection test
- Enter URL + token -> Test Connection shows success (Y/N)

## 3) Post-connect behavior
- After successful connect, app remains usable (does not self-minimize/close) (Y/N)

If any check fails, export logs:

```powershell
.\adb logcat -c
.\adb logcat AndroidRuntime:E ActivityTaskManager:I ActivityManager:I OpenClaw*:V *:S > failure.txt
```

Reproduce once, stop capture with Ctrl+C, and share `failure.txt`.
