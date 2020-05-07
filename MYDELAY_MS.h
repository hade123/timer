
/*
 * mydelay_ms.h
 */ 
// the "NOP" takes 1 Cycle only
#include "Data_type.h"
#ifndef MYDELAY_MS_H_
#define MYDELAY_MS_H_
	#define clock   8000000
	#define error_ratio 30
	u32 i;
	u32 n;
	
	DELAY_MS(delay){
		
		n=(delay *clock)/2;
		for(i=1; i<= n; i++){
			
		}
		
	}
	
	

#endif /* MYDELAY_MS_H_ */