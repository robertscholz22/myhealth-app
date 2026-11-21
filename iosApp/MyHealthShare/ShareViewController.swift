import UIKit
import UniformTypeIdentifiers

/// "Share → MyHealth" (P26.8). A share extension cannot open its app, so it parks the shared link
/// (and the sharing app's title, if any) in the App Group; MyHealth picks it up the next time it
/// becomes active (`consumeSharedVideo` in `MainViewController.kt`) and opens "New video workout".
final class ShareViewController: UIViewController {
    private static let group = "group.io.github.robertscholz22.myhealth"
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.text = "Saving…"
        label.textAlignment = .center
        label.numberOfLines = 0
        label.font = .preferredFont(forTextStyle: .headline)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
        Task { await collect() }
    }

    /// The first web link and the first text among the shared items, plus the item's title.
    private func collect() async {
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        var url: String?
        var text = items.first?.attributedContentText?.string
        let subject = items.first?.attributedTitle?.string
        for item in items {
            for provider in item.attachments ?? [] {
                if url == nil, provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    let value = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier)
                    url = (value as? URL)?.absoluteString ?? (value as? String)
                } else if text?.isEmpty ?? true, provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    text = (try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier)) as? String
                }
            }
        }
        let shared = [text, url].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        finish(saved: shared.contains("http") && save(text: shared, subject: subject))
    }

    private func save(text: String, subject: String?) -> Bool {
        guard let defaults = UserDefaults(suiteName: Self.group) else { return false }
        defaults.set(text, forKey: "sharedText")
        defaults.set(subject, forKey: "sharedSubject")
        return true
    }

    private func finish(saved: Bool) {
        label.text = saved
            ? "Saved to MyHealth.\nOpen MyHealth to finish the video workout."
            : "No link found to save."
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
