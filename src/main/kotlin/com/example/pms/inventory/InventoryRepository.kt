package com.example.pms.inventory

interface InventoryRepository {

	/**
	 * 숙박일 재고 행을 날짜순으로 잠근 뒤 조회
	 * 겹치는 연박 요청 간 교착 상태 방지를 위해 잠금 순서를 날짜순으로 고정
	 */
	fun findAndLock(roomTypeId: Long, period: StayPeriod): StayInventory

	fun save(inventory: StayInventory)
}
