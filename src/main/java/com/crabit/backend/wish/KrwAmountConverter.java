package com.crabit.backend.wish;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class KrwAmountConverter implements AttributeConverter<KrwAmount, Long> {

	@Override
	public Long convertToDatabaseColumn(KrwAmount amount) {
		return amount == null ? null : amount.won();
	}

	@Override
	public KrwAmount convertToEntityAttribute(Long won) {
		return won == null ? null : KrwAmount.of(won);
	}
}
