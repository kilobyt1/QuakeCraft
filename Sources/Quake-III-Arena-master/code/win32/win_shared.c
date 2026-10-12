/*
===========================================================================
Copyright (C) 1999-2005 Id Software, Inc.

This file is part of Quake III Arena source code.

Quake III Arena source code is free software; you can redistribute it
and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation; either version 2 of the License,
or (at your option) any later version.

Quake III Arena source code is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Foobar; if not, write to the Free Software
Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
===========================================================================
*/

#include "../game/q_shared.h"
#include "../qcommon/qcommon.h"
#include "win_local.h"
#include <lmerr.h>
#include <lmcons.h>
#include <lmwksta.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <direct.h>
#include <io.h>
#include <conio.h>

/*
================
Sys_Milliseconds
================
*/
int			sys_timeBase;
int Sys_Milliseconds (void)
{
	int			sys_curtime;
	static qboolean	initialized = qfalse;

	if (!initialized) {
		sys_timeBase = timeGetTime();
		initialized = qtrue;
	}
	sys_curtime = timeGetTime() - sys_timeBase;

	return sys_curtime;
}

/*
================
Sys_SnapVector
================
*/
#if id386 && defined(_MSC_VER)
long fastftol( float f ) {
	static int tmp;
	__asm fld f
	__asm fistp tmp
	__asm mov eax, tmp
}

void Sys_SnapVector( float *v )
{
	int i;
	float f;

	f = *v;
	__asm	fld		f;
	__asm	fistp	i;
	*v = i;
	v++;
	f = *v;
	__asm	fld		f;
	__asm	fistp	i;
	*v = i;
	v++;
	f = *v;
	__asm	fld		f;
	__asm	fistp	i;
	*v = i;
}
#else
long fastftol( float f ) {
	return (long)f;
}

void Sys_SnapVector( float *v ) {
	v[0] = (float)(int)v[0];
	v[1] = (float)(int)v[1];
	v[2] = (float)(int)v[2];
}
#endif

/*
** --------------------------------------------------------------------------------
**
** PROCESSOR STUFF
**
** --------------------------------------------------------------------------------
*/
int Sys_GetProcessorId( void )
{
	return CPUID_GENERIC;
}

/*
**
** Re-enable optimizations back to what they were
**
*/
#pragma optimize( "", on )

//============================================

char *Sys_GetCurrentUser( void )
{
	static char s_userName[1024];
	unsigned long size = sizeof( s_userName );


	if ( !GetUserName( s_userName, &size ) )
		strcpy( s_userName, "player" );

	if ( !s_userName[0] )
	{
		strcpy( s_userName, "player" );
	}

	return s_userName;
}

char	*Sys_DefaultHomePath(void) {
	return NULL;
}

char *Sys_DefaultInstallPath(void)
{
	return Sys_Cwd();
}

