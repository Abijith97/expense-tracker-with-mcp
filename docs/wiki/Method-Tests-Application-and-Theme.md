# Method tests: application startup and theme

[Index and evidence legend](Home.md) | [Cloud settings](Method-Tests-Cloud-Settings.md)

Sources: [FinanceTrackerApplication.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/FinanceTrackerApplication.kt), [Theme.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ui/theme/Theme.kt). Existing integration/UI suites incidentally exercise these entry points; neither has a dedicated contract suite.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| AT-01 `FinanceTrackerApplication.onCreate()` | Restore cloud settings on executor even when process starts without screen | **I:** app launch runs startup. **G:** cold receiver/worker-only startup with synthetic remembered credentials, one synchronized restore, off-main-thread work and fail-closed error. Do not kill/modify user's real process/settings. |
| AT-02 `FinanceTrackerCompanionTheme()` | Supply light/dark/dynamic Material colors and typography | **I:** Compose UI suites render within theme. **G:** light/dark and dynamic-color enabled/disabled matrix across pre/post API 31; semantic interactions remain readable/accessible. Passing UI assertions is not visual/accessibility certification. |

[Color.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ui/theme/Color.kt) and [Type.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ui/theme/Type.kt) define values with no explicitly named functions. [ComposeTestActivity.kt](../../android-companion/app/src/debug/java/com/abijith/expensetracker/ComposeTestActivity.kt) is an empty debug-only host class, with no declared methods. It supports isolated UI tests and is absent from release; no fake method test is listed for it.

Manual theme/device review is **M**. Tests should not hardcode device-dependent dynamic color values or mistake coverage of one theme branch for validation of all appearance variants.
