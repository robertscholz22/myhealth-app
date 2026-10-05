import XCTest

/// Simulator walkthrough run by `.github/workflows/ios.yml` (P21). Screenshots go to the directory
/// in `SCREENSHOT_DIR` (set via `TEST_RUNNER_SCREENSHOT_DIR`) and into the result bundle; the
/// accessibility tree of each step is written next to them for review on Linux.
final class LaunchTests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            let base = URL(fileURLWithPath: dir)
            try? screenshot.pngRepresentation.write(to: base.appendingPathComponent("\(name).png"))
            try? app.debugDescription.write(
                to: base.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
        }
    }

    /// Waits for an element, capturing the screen under [name] either way.
    private func expect(_ element: XCUIElement, _ app: XCUIApplication, _ name: String, timeout: TimeInterval = 30) {
        let found = element.waitForExistence(timeout: timeout)
        capture(app, name)
        XCTAssertTrue(found, "\(name): \(element) did not appear")
    }

    private func label(containing text: String, in app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// A text field by its label; Compose appends the supporting text ("Height, Height must be …").
    private func field(_ label: String, in app: XCUIApplication) -> XCUIElement {
        app.textViews.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
    }

    /// The three onboarding steps with fixed answers, ending on Today.
    private func completeOnboarding(_ app: XCUIApplication) {
        field("Name", in: app).tap()
        app.typeText("Alex")
        // The birth date field opens a date picker; switch it to text input.
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 363.0 / 874.0)).tap()
        expect(app.buttons["Switch to text input mode"], app, "02_date_picker")
        app.buttons["Switch to text input mode"].tap()
        let dateField = app.textViews.matching(NSPredicate(format: "label CONTAINS 'Date'")).firstMatch
        expect(dateField, app, "03_date_input")
        dateField.tap()
        app.typeText("01151992")
        capture(app, "04_date_typed")
        app.buttons["OK"].tap()
        expect(app.buttons["Next"], app, "05_step1_filled")
        app.buttons["Next"].tap()

        expect(app.staticTexts["Step 2 of 3: Body & goals"], app, "06_step2")
        field("Height", in: app).tap()
        app.typeText("175")
        field("Current weight", in: app).tap()
        app.typeText("72")
        capture(app, "07_step2_filled")
        // A tap outside the field closes the keyboard (there is no back key on an iPhone).
        app.staticTexts["Step 2 of 3: Body & goals"].tap()
        sleep(1)
        capture(app, "07b_keyboard_closed")
        XCTAssertFalse(app.keyboards.firstMatch.exists, "the keyboard stayed open after a tap outside")
        app.buttons["Next"].tap()

        expect(app.staticTexts["Step 3 of 3: Preferences"], app, "08_step3")
        app.buttons["Finish"].tap()
    }

    /// Fresh install → onboarding → Today, the main tabs, and a relaunch that skips onboarding
    /// (profile, settings and the nutrition target persisted in the iOS database and DataStore).
    func testOnboardingToTodayAndRelaunch() {
        let app = XCUIApplication()
        app.launch()
        expect(app.staticTexts["Step 1 of 3: About you"], app, "01_onboarding", timeout: 90)
        completeOnboarding(app)

        expect(label(containing: "kcal left", in: app), app, "09_today", timeout: 60)
        for tab in ["Calendar", "Training", "More"] {
            let button = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", tab)).firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 10), "tab \(tab) not found")
            button.tap()
            sleep(2)
            capture(app, "10_tab_\(tab.lowercased())")
        }

        app.terminate()
        app.launch()
        expect(label(containing: "kcal left", in: app), app, "11_relaunch_today", timeout: 60)
        XCTAssertFalse(app.staticTexts["Step 1 of 3: About you"].exists, "onboarding shown again after relaunch")
    }

    private func tab(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }

    /// Taps a More-screen entry, scrolling down to it when needed.
    private func openEntry(_ name: String, in app: XCUIApplication) {
        let entry = app.staticTexts[name]
        var swipes = 0
        while !(entry.exists && entry.isHittable) && swipes < 6 {
            app.swipeUp()
            swipes += 1
        }
        entry.tap()
    }

    /// Answers HealthKit's permission sheet: "Turn On All", then "Allow".
    private func allowHealthAccess(_ app: XCUIApplication, _ name: String) {
        let turnOnAll = app.descendants(matching: .any)["Turn On All"]
        expect(turnOnAll, app, name, timeout: 60)
        turnOnAll.tap()
        let allow = app.descendants(matching: .any)["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "\(name): no Allow button")
        capture(app, "\(name)_all_on")
        allow.tap()
    }

    /// P22.1: the seeder fills Apple Health, the Integrations screen connects it, the shared sync
    /// pulls workouts, daily totals, sleep and weight into the app.
    func testSyncAppleHealth() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedHealthKit"]
        app.launch()
        allowHealthAccess(app, "20_seeder_sheet")
        if app.staticTexts["Step 1 of 3: About you"].waitForExistence(timeout: 30) {
            completeOnboarding(app)
        }
        expect(label(containing: "kcal left", in: app), app, "21_today_before_sync", timeout: 60)

        tab("More", in: app).tap()
        openEntry("Integrations", in: app)
        let connect = app.buttons["Connect Apple Health"]
        expect(connect, app, "22_integrations")
        XCTAssertTrue(label(containing: "not connected to Apple Health yet", in: app).exists)
        connect.tap()
        allowHealthAccess(app, "23_read_sheet")
        expect(label(containing: "Access to Apple Health has been requested", in: app), app, "24_connected")

        // Connecting starts a sync; every channel then shows its time instead of "Never synced".
        for channel in ["Workouts", "Daily activity", "Sleep", "Body measurements"] {
            let done = app.staticTexts.matching(
                NSPredicate(format: "label BEGINSWITH %@ AND NOT (label CONTAINS 'Never')", "\(channel): ")).firstMatch
            XCTAssertTrue(done.waitForExistence(timeout: 180), "\(channel) did not sync")
        }
        XCTAssertFalse(label(containing: "Last error", in: app).exists, "a sync channel reported an error")
        capture(app, "25_synced")
        app.swipeUp()
        capture(app, "26_synced_backfill")

        app.buttons["Back"].tap()
        openEntry("Activities", in: app)
        expect(label(containing: "Run", in: app), app, "27_activities", timeout: 30)
        XCTAssertTrue(label(containing: "Soccer", in: app).exists, "no soccer session synced")
        XCTAssertTrue(label(containing: "Apple Health", in: app).exists, "source badge does not say Apple Health")
        label(containing: "Run", in: app).tap()
        sleep(3)
        capture(app, "28_activity_detail")
        app.swipeUp()
        sleep(1)
        capture(app, "29_activity_detail_hr")
        app.buttons["Back"].tap()
        app.buttons["Back"].tap()

        openEntry("Load & Recovery", in: app)
        sleep(5)
        capture(app, "30_load_recovery")
        app.buttons["Back"].tap()

        tab("Today", in: app).tap()
        sleep(3)
        capture(app, "31_today_after_sync")
        tab("Calendar", in: app).tap()
        sleep(3)
        capture(app, "32_calendar_after_sync")
    }
}
