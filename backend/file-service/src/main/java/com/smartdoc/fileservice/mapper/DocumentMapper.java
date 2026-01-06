package com.smartdoc.fileservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.fileservice.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}

