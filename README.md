
## Overview	
	
threefiredb是一个基于leveldb并使用java开发的单机内嵌式持久型的kv存储系统（目前部分核心和扩展功能使用经过修改的rocksdb jni 实现。  	
threefiredb 提供了丰富的数据结构 如KV、List、Map、ZSET等。也提供了TTL（生存时间）、备份、ACID事功能。   	
threefiredb完全基于磁盘存储，并提供最高百万级别的查询性能和十万的写入性能。   
	
## Features	
	
- 完全基于磁盘，不受内存限制	
- KV、List、Map、Zet、ZSET等丰富的数据结构	
- 最高百万级别的查询性能和十万级的写入性能	
- 原子性写入，读写无冲突	
- TTL（生存时间）	
- 备份与恢复	
- ACID事务
	
	
## Requirements	
编译要求：JDK 8+和Maven 3.2.5+	
	
> 单元测试默认数据存储路径为/data/threefiredb 如需调整，可用-Dkitdb_path 指定，例如：	
```	
test -Dthreefiredb_path=D:\\temp\\db -f pom.xml	
``	
	
	
## Explain	
store模块为threefiredb本体，raft模块为官方Raft协议插件	
	
	
## 操作系统兼容问题	
对常用进行开发和运行环境的**操作系统**进行测试，操作系统使用**官方镜像重新安装，排除干扰**。开发环境IDE使用IntelliJ IDEA Community。	
   	
操作系统 |系统位数|环境| Java虚拟机 | Java虚拟机版本 | 结果	
---|---|---|---|---|---	
Windows 10|64 | 开发| OpenJDK 64-Bit Server VM | 13.0.2+8| 通过	
Windows 7 |64| 开发 | OpenJDK 64-Bit Server VM| 11.0.5+10-b520.388|  通过	
Windows Server 2008 R2  |64| 运行| OpenJDK 64-Bit Server VM | 13.0.2+8|  通过	
Ubuntu 18.04 |64| 运行 | OpenJDK 64-Bit Server VM| 11.0.6+10-post-Ubuntu-1ubuntu118.04.1 |  通过	
Ubuntu 16.04 |64| 运行 | OpenJDK 64-Bit Server VM| 9-internal+0-2016-04-14-195246.buildd.src | 通过	
CentOS 8.0   |64| 运行 | OpenJDK 64-Bit Server VM| 11.0.5+10-LTS|  通过	
