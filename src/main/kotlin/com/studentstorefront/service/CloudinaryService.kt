package com.studentstorefront.service

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream

@Service
class CloudinaryService(
    @Value("\${cloudinary.cloud-name}") private val cloudName: String,
    @Value("\${cloudinary.api-key}") private val apiKey: String,
    @Value("\${cloudinary.api-secret}") private val apiSecret: String
) {

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

    fun uploadImage(file: MultipartFile): String? {
        return try {
            val uploadedFile = convertMultiPartToFile(file)
            val uploadResult = cloudinary.uploader().upload(uploadedFile, ObjectUtils.emptyMap())
            uploadedFile.delete()
            uploadResult["secure_url"] as String
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertMultiPartToFile(file: MultipartFile): File {
        val convFile = File(System.getProperty("java.io.tmpdir") + "/" + (file.originalFilename ?: "temp_upload"))
        val fos = FileOutputStream(convFile)
        fos.write(file.bytes)
        fos.close()
        return convFile
    }
}
