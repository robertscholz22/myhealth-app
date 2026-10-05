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
        let turnOnAll = app.descendants(matching: .any)["Turn On All"].firstMatch
        expect(turnOnAll, app, name, timeout: 60)
        turnOnAll.tap()
        let allow = app.buttons["UIA.Health.Allow.Button"].firstMatch
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

    /// Picks the photo at [index] in the system photo picker (newest first). CI adds the label
    /// last, so it is the first photo, the barcode the second.
    private func pickPhoto(_ index: Int, _ app: XCUIApplication, _ name: String) {
        let photo = app.descendants(matching: .image).matching(NSPredicate(format: "label BEGINSWITH 'Photo'")).element(boundBy: index)
        expect(photo, app, name, timeout: 30)
        photo.tap()
    }

    /// P22.3: the simulator has no camera, so the scan screen offers "From photo"; Vision reads
    /// the nutrition label test image into "Check the scan", and the barcode test image is looked
    /// up on Open Food Facts.
    func testScanFromPhoto() {
        let app = XCUIApplication()
        app.launch()
        if app.staticTexts["Step 1 of 3: About you"].waitForExistence(timeout: 30) {
            completeOnboarding(app)
        }
        expect(label(containing: "kcal left", in: app), app, "40_today", timeout: 60)
        tab("Nutrition", in: app).tap()
        let add = app.buttons["+ Add"].firstMatch
        expect(add, app, "41_nutrition")
        add.tap()
        let scanTab = app.descendants(matching: .any)["Scan"].firstMatch
        expect(scanTab, app, "42_add_food")
        scanTab.tap()

        let fromPhoto = app.buttons.matching(NSPredicate(format: "label CONTAINS 'From photo'")).firstMatch
        expect(fromPhoto, app, "43_scan_no_camera")
        XCTAssertTrue(label(containing: "No camera", in: app).exists, "the no-camera state is not shown")
        fromPhoto.tap()
        pickPhoto(0, app, "44_photo_picker")
        expect(app.staticTexts["Check the scan"], app, "45_ocr_review", timeout: 60)
        XCTAssertTrue(label(containing: "373", in: app).exists, "the energy value was not recognised")
        app.swipeUp()
        capture(app, "46_ocr_review_values")
        app.buttons["Back"].firstMatch.tap()

        // Barcode mode: the second photo carries an EAN-13.
        let barcode = app.descendants(matching: .any)["Barcode"].firstMatch
        expect(barcode, app, "47_scan_again")
        barcode.tap()
        fromPhoto.tap()
        pickPhoto(1, app, "48_photo_picker_barcode")
        let code = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "3017620422003", "3017620422003")).firstMatch
        expect(code, app, "49_barcode_result", timeout: 60)
        app.swipeUp()
        capture(app, "50_barcode_result_values")
    }

    /// An element of the system file picker whose label contains [text].
    private func pickerItem(_ text: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Opens [file] in the Files picker: straight from Recents, or via Browse › On My iPhone.
    private func pickFile(_ file: String, _ app: XCUIApplication, _ name: String) {
        let item = pickerItem(file, in: app)
        if !item.waitForExistence(timeout: 10) {
            capture(app, "\(name)_recents")
            let browse = app.buttons["Browse"].firstMatch
            if browse.exists { browse.tap() }
            let onPhone = pickerItem("On My iPhone", in: app)
            if onPhone.waitForExistence(timeout: 10) { onPhone.tap() }
        }
        expect(item, app, name, timeout: 20)
        item.tap()
    }

    /// P22.2: a FIT file imported from Files, then a backup exported to Files and imported back.
    /// CI puts `run_5k.fit` into Files › On My iPhone before the tests run.
    func testUsesFilesForImportAndBackup() {
        let app = XCUIApplication()
        app.launch()
        if app.staticTexts["Step 1 of 3: About you"].waitForExistence(timeout: 30) {
            completeOnboarding(app)
        }
        expect(label(containing: "kcal left", in: app), app, "60_today", timeout: 60)

        tab("More", in: app).tap()
        openEntry("Import", in: app)
        let choose = app.buttons["Choose file"].firstMatch
        expect(choose, app, "61_import")
        choose.tap()
        pickFile("run_5k", app, "62_file_picker")
        let imported = app.staticTexts.matching(
            NSPredicate(format: "label == 'Import finished' OR label == 'Already imported'")).firstMatch
        expect(imported, app, "63_fit_imported", timeout: 60)
        app.buttons["Back"].firstMatch.tap()

        openEntry("Backup", in: app)
        let export = app.buttons["Export backup"].firstMatch
        expect(export, app, "64_backup")
        export.tap()
        // The "save to Files" sheet: keep its proposed place and confirm.
        let save = app.buttons.matching(NSPredicate(format: "label IN {'Save', 'Move', 'Done'}")).firstMatch
        expect(save, app, "65_export_sheet", timeout: 30)
        save.tap()
        expect(app.staticTexts["Export finished"], app, "66_exported", timeout: 30)

        let restore = app.buttons["Import backup"].firstMatch
        if !restore.isHittable { app.swipeUp() }
        restore.tap()
        pickFile("myhealth-backup", app, "67_backup_picker")
        expect(app.staticTexts["Import finished"], app, "68_backup_imported", timeout: 60)
        app.swipeUp()
        capture(app, "69_backup_imported_counts")
    }
}
