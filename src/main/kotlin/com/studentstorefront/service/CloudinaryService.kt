package com.studentstorefront.service

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.nio.file.Files

@Service
class CloudinaryService(
    @Value("\${cloudinary.cloud-name}") private val cloudName: String,
    @Value("\${cloudinary.api-key}") private val apiKey: String,
    @Value("\${cloudinary.api-secret}") private val apiSecret: String
) {

    data class UploadedImage(val url: String, val publicId: String?)

    private val cloudinary: Cloudinary by lazy {
        Cloudinary(
            ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true
            )
        )
    }

    class ImageUploadException(cause: Throwable) : RuntimeException("Failed to upload image", cause)

    fun uploadImage(file: MultipartFile): String = uploadImageAsset(file).url

    /**
     * Uploads one listing image. Validation problems are user errors
     * (IllegalArgumentException); a Cloudinary failure is logged with its cause
     * and surfaced as [ImageUploadException] instead of a silent null (BE-11).
     */
    fun uploadImageAsset(file: MultipartFile): UploadedImage {
        require(file.size <= MAX_FILE_BYTES) { "Image must be at most 10 MB" }
        require(file.contentType?.startsWith("image/") == true) { "Only image uploads are supported" }
        var uploadedFile: File? = null
        return try {
            uploadedFile = convertMultiPartToFile(file)
            // The client's Content-Type is only a claim; check the file signature.
            require(ImageSignature.isSupportedImage(uploadedFile)) { "Only image uploads are supported" }
            val uploadResult = cloudinary.uploader().upload(
                uploadedFile,
                ObjectUtils.asMap("folder", "student-storefront")
            )
            val url = uploadResult["secure_url"] as? String
                ?: throw IllegalStateException("Cloudinary did not return a secure URL")
            UploadedImage(url = url, publicId = uploadResult["public_id"]?.toString())
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(javaClass).warn("Cloudinary image upload failed", e)
            throw ImageUploadException(e)
        } finally {
            uploadedFile?.let {
                runCatching { Files.deleteIfExists(it.toPath()) }
                    .onFailure { cleanupError ->
                        org.slf4j.LoggerFactory.getLogger(javaClass)
                            .warn("Temporary upload cleanup failed for {}", it, cleanupError)
                    }
            }
        }
    }

    fun deleteImage(publicId: String) {
        if (publicId.isBlank()) return
        runCatching {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("invalidate", true))
        }.onFailure {
            org.slf4j.LoggerFactory.getLogger(javaClass).warn("Cloudinary image cleanup failed for {}", publicId, it)
        }
    }

    private fun convertMultiPartToFile(file: MultipartFile): File {
        val tempFile = Files.createTempFile("student-store-upload-", ".upload").toFile()
        try {
            var totalBytes = 0L
            file.inputStream.use { input ->
                Files.newOutputStream(tempFile.toPath()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        require(totalBytes <= MAX_FILE_BYTES) { "Image must be at most 10 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return tempFile
        } catch (ex: Exception) {
            Files.deleteIfExists(tempFile.toPath())
            throw ex
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 10L * 1024L * 1024L
    }
}
