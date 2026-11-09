package app.resound.backup

/** 官方依据及限制见 docs/任务卡/114-交付说明.md；地址中不包含桶名。 */
internal object CloudPresets {
    val all = listOf(
        S3Preset("oss", "阿里云 OSS", "https://oss-{region}.aliyuncs.com", listOf("cn-hangzhou", "cn-shanghai", "cn-beijing", "cn-shenzhen"), false,
            "用 RAM 访问密钥；可能须联系阿里云开启 S3 兼容认证，OSS 原生 V4 不是 AWS V4；仅支持虚拟主机式"),
        S3Preset("cos", "腾讯云 COS", "https://cos.{region}.myqcloud.com", listOf("ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"), false,
            "桶名必须包含 APPID 后缀；2024 年起新桶仅支持虚拟主机式，使用 SecretId / SecretKey"),
        S3Preset("obs", "华为云 OBS", "https://obs.{region}.myhuaweicloud.com", listOf("cn-north-4", "cn-east-3", "cn-south-1"), false,
            "使用 IAM AK/SK；地址不要带桶名；本预设用 HTTPS 和 V4 UNSIGNED-PAYLOAD 兼容 OBS"),
        S3Preset("kodo", "七牛云 Kodo", "https://s3.{region}.qiniucs.com", listOf("cn-east-1", "cn-east-2", "cn-north-1", "cn-south-1", "ap-southeast-1"), true,
            "填写空间概览中的 S3 空间名（可能不同于原空间名）和 AK/SK，支持两种地址格式"),
        S3Preset("r2", "Cloudflare R2", "https://{accountId}.r2.cloudflarestorage.com", listOf("auto"), true,
            "用 R2 S3 API 访问密钥，区域填 auto；替换账户 ID，管辖区域桶需使用对应专用地址"),
        S3Preset("aws", "AWS S3", "https://s3.{region}.amazonaws.com", listOf("us-east-1", "us-west-2", "ap-northeast-1", "ap-southeast-1", "eu-central-1"), false,
            "区域必须与桶一致；使用长期 IAM 访问密钥，暂不支持 STS 会话令牌；中国区域另填 .amazonaws.com.cn 地址"),
        S3Preset("b2", "Backblaze B2", "https://s3.{region}.backblazeb2.com", listOf("us-west-004", "us-east-005", "eu-central-003"), true,
            "以控制台桶地址为准，用 Application Key ID / Key；仅 HTTPS，同一对象避免一秒内连续覆盖或删除"),
        S3Preset("minio", "MinIO（自建）", "http://{host}:9000", listOf("us-east-1"), true,
            "填 S3 API 端口（通常 9000），不是控制台端口；HTTP 仅限私网 IP 或 .local，区域按管理员配置"),
        S3Preset("custom", "自定义", "", emptyList(), true,
            "填写支持 AWS SigV4 和 ListObjectsV2 的服务地址；私有访问密钥只存安全区"),
    )
}
