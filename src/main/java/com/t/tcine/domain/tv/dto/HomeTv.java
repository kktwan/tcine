package com.t.tcine.domain.tv.dto;

/** 시리즈(TV) 화면 첫머리에 보여줄 인기/신작 한 편 */
public record HomeTv(int id, String title, Integer year, Double rating, String network, String posterUrl, String query) {
}
