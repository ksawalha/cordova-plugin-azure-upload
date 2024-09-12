import Foundation
import UIKit
import AVFoundation
import UserNotifications

@objc(AzureUpload) class AzureUpload: CDVPlugin {

    let STORAGE_URL = "https://arabicschool.blob.core.windows.net/"
    let channelId = "azure_upload_channel"

    @objc(uploadFiles:) 
    func uploadFiles(command: CDVInvokedUrlCommand) {
        let postId = command.arguments[0] as! String
        let sasToken = command.arguments[1] as! String
        let files = command.arguments[2] as! [[String: Any]]
        
        let queue = DispatchQueue(label: "upload.queue", attributes: .concurrent)
        createNotificationChannel()

        for file in files {
            let fileName = file["filename"] as! String
            let originalName = file["originalname"] as! String
            let mimeType = file["mimetype"] as! String
            let binaryData = file["binarydata"] as! String
            let thumbnail = file["thumbnail"] as! String
            
            if mimeType.starts(with: "image") {
                queue.async {
                    self.uploadImage(fileName, binaryData: binaryData, sasToken: sasToken, originalName: originalName, postId: postId)
                }
            } else if mimeType.starts(with: "video") {
                queue.async {
                    self.uploadVideo(fileName, binaryData: binaryData, thumbnail: thumbnail, sasToken: sasToken, originalName: originalName, postId: postId)
                }
            } else {
                queue.async {
                    self.uploadFile(fileName, binaryData: binaryData, sasToken: sasToken, originalName: originalName, postId: postId)
                }
            }
        }
    }

    private func uploadImage(_ fileName: String, binaryData: String, sasToken: String, originalName: String, postId: String) {
        if let imageData = Data(base64Encoded: binaryData) {
            if let image = UIImage(data: imageData) {
                if let pngData = image.pngData() {
                    uploadToAzure(fileName, fileData: pngData, sasToken: sasToken, originalName: originalName, postId: postId)
                }
            }
        }
    }

    private func uploadVideo(_ fileName: String, binaryData: String, thumbnail: String, sasToken: String, originalName: String, postId: String) {
        if let videoData = Data(base64Encoded: binaryData) {
            uploadToAzure(fileName, fileData: videoData, sasToken: sasToken, originalName: originalName, postId: postId)
            
            let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("tempVideo.mp4")
            do {
                try videoData.write(to: tempURL)
                if let thumbnailData = generateThumbnail(from: tempURL) {
                    uploadToAzure(thumbnail, fileData: thumbnailData, sasToken: sasToken, originalName: originalName, postId: postId)
                }
            } catch {
                print("Error saving video data: \(error.localizedDescription)")
            }
        }
    }

    private func uploadFile(_ fileName: String, binaryData: String, sasToken: String, originalName: String, postId: String) {
        if let fileData = Data(base64Encoded: binaryData) {
            uploadToAzure(fileName, fileData: fileData, sasToken: sasToken, originalName: originalName, postId: postId)
        }
    }

    private func uploadToAzure(_ fileName: String, fileData: Data, sasToken: String, originalName: String, postId: String) {
        let urlString = "\(STORAGE_URL)\(fileName)?\(sasToken)"
        guard let url = URL(string: urlString) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        
        let task = URLSession.shared.uploadTask(with: request, from: fileData) { _, response, error in
            if let error = error {
                print("Error uploading to Azure: \(error.localizedDescription)")
                self.showNotification(title: "Upload Error", content: "Failed to upload \(originalName)")
            } else {
                self.commitUpload(postId: postId, fileUrl: "\(STORAGE_URL)\(fileName)", originalName: originalName, mimeType: "application/octet-stream")
                self.showNotification(title: "Upload Complete", content: "File \(originalName) uploaded successfully.")
            }
        }
        task.resume()
    }

    private func commitUpload(postId: String, fileUrl: String, originalName: String, mimeType: String) {
        let urlString = "https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=\(postId)"
        guard let url = URL(string: urlString) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = [
            "URL": fileUrl,
            "originalname": originalName,
            "filemime": mimeType
        ]
        
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: body, options: [])
            let task = URLSession.shared.uploadTask(with: request, from: jsonData) { _, response, error in
                if let error = error {
                    print("Error committing upload: \(error.localizedDescription)")
                }
            }
            task.resume()
        } catch {
            print("Error creating JSON for commit: \(error.localizedDescription)")
        }
    }

    private func generateThumbnail(from url: URL) -> Data? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        do {
            let thumbnailImage = try imageGenerator.copyCGImage(at: CMTime.zero, actualTime: nil)
            let image = UIImage(cgImage: thumbnailImage)
            return image.pngData()
        } catch {
            print("Error generating thumbnail: \(error.localizedDescription)")
            return nil
        }
    }

    private func createNotificationChannel() {
        if #available(iOS 10.0, *) {
            let center = UNUserNotificationCenter.current()
            center.requestAuthorization(options: [.alert, .sound]) { granted, error in
                if granted {
                    let content = UNMutableNotificationContent()
                    content.title = "Azure Uploads"
                    content.body = "Notifications for file uploads to Azure"
                    content.sound = UNNotificationSound.default
                    let request = UNNotificationRequest(identifier: self.channelId, content: content, trigger: nil)
                    center.add(request) { error in
                        if let error = error {
                            print("Error adding notification request: \(error.localizedDescription)")
                        }
                    }
                }
            }
        }
    }

    private func showNotification(title: String, content: String) {
        if #available(iOS 10.0, *) {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = content
            content.sound = UNNotificationSound.default
            
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            UNUserNotificationCenter.current().add(request) { error in
                if let error = error {
                    print("Error showing notification: \(error.localizedDescription)")
                }
            }
        }
    }
}
