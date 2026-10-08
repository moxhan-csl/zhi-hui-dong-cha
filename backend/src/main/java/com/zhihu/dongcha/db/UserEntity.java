package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/**
 * users：业务主账号（password 存 BCrypt 哈希；启动时会把遗留明文原地升级）
 * <p>D-2 不给这张表补索引：登录查的是 {@code findByAccountIgnoreCase / findByEmpNoIgnoreCase}，
 * Spring Data 生成的是 {@code lower(col)=lower(?)}，函数包住列之后索引就用不上了；
 * 而 users 是全站最小的一张表（量级由管理员控制），登录也只在输入口令那一次发生。
 * account 上的 {@code unique=true} 是全项目唯一的数据库级唯一约束，也是历史遗留；
 * 工号的唯一性改由写入侧代码判定（见 {@code SettingsController.requireDistinctIdentity}）。
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "name", length = 60)
    public String name;

    @Column(name = "account", length = 120, unique = true)
    public String account;

    @Column(name = "emp_no", length = 60)
    public String empNo;

    @Column(name = "password", length = 120)
    public String password;

    /** 角色标识（如 SYS_ADMIN / KM_ADMIN，合法集见 SettingsController.ROLES）；AuthWebFilter 按它走权限矩阵 */
    @Column(name = "role", length = 20)
    public String role;

    @Column(name = "dept", length = 60)
    public String dept;

    /** 启用开关：false 时 AuthController 拒绝登录、AuthWebFilter 直接拦截；停用不删行，历史数据保留 */
    @Column(name = "enabled")
    public boolean enabled = true;
}
