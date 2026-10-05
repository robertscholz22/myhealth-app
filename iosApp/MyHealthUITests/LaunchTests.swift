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

    /// Fresh install → onboarding → Today, the main tabs, and a relaunch that skips onboarding
    /// (profile, settings and the nutrition target persisted in the iOS database and DataStore).
    func testOnboardingToTodayAndRelaunch() {
        let app = XCUIApplication()
        app.launch()
        expect(app.staticTexts["Step 1 of 3: About you"], app, "01_onboarding", timeout: 90)

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
}
