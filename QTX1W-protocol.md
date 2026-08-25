# QTX1-W 白化二维码帧

QTX1-W 保留 QTX1 的 35 字符帧头、Base45 和 QR Code，只新增 `W` 数据帧。

`W` 帧 Payload：

```text
Seed 1 byte
Whitened file chunk N bytes
```

发送端用 Session ID、Block Index 和 Seed 初始化 XORSHIFT32，然后与原始分片逐字节异或。接收端对“Seed + Whitened Chunk”验证 CRC32；CRC 正确后，用相同序列再次异或恢复原始分片。

每轮 Seed 在 1～255 之间循环，因此同一个 Block 在不同补发轮次会得到不同二维码。最终文件仍必须通过初始化元数据中的 SHA-256 才允许保存。

旧 `D` 原始帧和 `R` 盐值帧仍可由 V1.1 接收端解析；V1.1 发送端只发送 `W` 数据帧。
