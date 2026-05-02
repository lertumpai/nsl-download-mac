import SwiftUI

struct URLInputView: View {
    @Binding var urlText: String
    @Binding var isProcessing: Bool
    let onSubmit: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            HStack {
                Image(systemName: "link")
                    .foregroundStyle(.secondary)
                    .padding(.leading, 6)

                TextField("Paste video URL here...", text: $urlText)
                    .textFieldStyle(.plain)
                    .focused($focused)
                    .onSubmit(onSubmit)

                if !urlText.isEmpty {
                    Button {
                        urlText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 4)
                }
            }
            .padding(.vertical, 6)
            .background(Color(nsColor: .controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(focused ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: 1)
            )

            Button(action: onSubmit) {
                if isProcessing {
                    ProgressView().scaleEffect(0.7).frame(width: 60)
                } else {
                    Text("Download")
                        .frame(width: 60)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isProcessing)
            .keyboardShortcut(.return, modifiers: [])
        }
    }
}
