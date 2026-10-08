package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** users 的仓库；DbStore 用它填鉴权镜像与取用户，登录与账号管理页都经 DbStore 转手 */
public interface UserRepository extends JpaRepository<UserEntity, String> {

    /** 全部账号按 id 升序（DbStore.allUsers 的读路，供管理页列表与统计在岗 SYS_ADMIN 数） */
    List<UserEntity> findAllByOrderByIdAsc();

    /** 按登录账号查（大小写不敏感）；生成的是 lower(col)=lower(?)，users 小表不建索引也能接受 */
    Optional<UserEntity> findByAccountIgnoreCase(String account);

    /** 账号查不到时按工号兜底查，DbStore.userByAccount 里 account→empNo 两段查的第二段 */
    Optional<UserEntity> findByEmpNoIgnoreCase(String empNo);
}
