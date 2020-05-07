/*
 * CFile1.c
 *
 * Created: 4/14/2020 8:42:34 AM
 *  Author: Administrator
 */ 
  
/*
 * EEPROM_MCAL.c */
#include "Register.h"
#include "stdmacros.h"
#include "Data_type.h"
void EEPROM_Write(u16 address , u8 value){
	myEEARL = (u8)address;      
	myEEARH= (u8)(address>>8);
	myEEDR = value;
	SET_BIT(myEECR , myEEMWE);
	SET_BIT(myEECR , myEEWE);
	while(READ_BIT(myEECR , myEEWE)==1);
}



u8 EEPROM_Read(const u16 address){
	myEEARL = (u8)address;
	myEEARH = (u8)(address>>8);
	SET_BIT(myEECR , myEERE);
	return myEEDR;
}