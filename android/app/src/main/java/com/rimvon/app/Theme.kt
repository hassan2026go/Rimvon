package com.rimvon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

val Blue = Color(0xFF5392D1)
val BlueDark = Color(0xFF2D68B0)
val BlueLight = Color(0xFFDCEBFA)
val GrayBg = Color(0xFFEBEBEB)
val GrayLine = Color(0xFFDDDDDD)
val TextGray = Color(0xFF9E9E9E)
val Ink = Color(0xFF1F2933)
val RedCall = Color(0xFFE5484D)
val GreenCall = Color(0xFF30A46C)

@Composable
fun RimvonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue, onPrimary = Color.White,
            background = Color.White, surface = Color.White, onSurface = Ink,
        )
    ) {
        // The whole app is Arabic, so the layout is always right-to-left whatever the phone language is.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Blue, contentColor = Color.White,
            disabledContainerColor = Blue.copy(alpha = 0.45f), disabledContentColor = Color.White,
        ),
    ) { Text(text, fontSize = 20.sp) }
}

@Composable
fun GrayButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GrayBg, contentColor = Color.Black),
    ) { Text(text, fontSize = 20.sp) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Blue),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue),
    ) { Text(text, fontSize = 20.sp) }
}

@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    textSize: TextUnit = 18.sp,
) {
    var show by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        placeholder = { Text(placeholder, color = TextGray) },
        textStyle = TextStyle(fontSize = textSize),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        trailingIcon = if (password) {
            {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null, tint = Blue)
                }
            }
        } else null,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Blue,
            unfocusedIndicatorColor = GrayLine,
        ),
    )
}

private val avatarColors = listOf(
    Color(0xFF5392D1), Color(0xFF4DB6AC), Color(0xFFE08A5B), Color(0xFF9575CD),
    Color(0xFFEF6C8D), Color(0xFF7CB342), Color(0xFFD4A72C), Color(0xFF6C7FD8),
)

@Composable
fun Avatar(name: String, id: String, size: Dp = 46.dp) {
    val letter = name.trim().firstOrNull { it.isLetter() }?.uppercase() ?: "#"
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColors[abs(id.hashCode()) % avatarColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BlueBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Blue).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1,
        )
        actions()
    }
}

/** state: 0 offline, 1 connecting, 2 online */
@Composable
fun ConnBanner(state: Int) {
    if (state == 2) return
    Box(
        Modifier.fillMaxWidth().background(Color(0xFFFFF4D6)).padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (state == 1) "جاري الاتصال بالخادم…" else "غير متصل بالخادم — تُحفظ رسائلك وتُرسل عند عودة الاتصال",
            fontSize = 13.sp, color = Color(0xFF7A5B00),
        )
    }
}

@Composable
fun EmptyState(title: String, sub: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(80.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(sub, fontSize = 15.sp, color = TextGray)
    }
}

fun fmtTime(ts: Long): String {
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "d/M", Locale.US).format(Date(ts))
}

fun fmtClock(ts: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

fun fmtDur(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
