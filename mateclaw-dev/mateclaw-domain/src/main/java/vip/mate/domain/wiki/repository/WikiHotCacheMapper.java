package vip.mate.domain.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.wiki.model.WikiHotCacheEntity;

@Mapper
public interface WikiHotCacheMapper extends BaseMapper<WikiHotCacheEntity> {
}
