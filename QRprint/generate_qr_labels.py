import os
from datetime import datetime
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
import qrcode

def generate_qr_pdf():
    # --- 日本語フォントの登録 ---
    pdfmetrics.registerFont(UnicodeCIDFont('HeiseiKakuGo-W5'))
    
    # --- 時刻の取得 ---
    now = datetime.now()
    timestamp_fs = now.strftime("%Y%m%d_%H%M%S")
    base_time_str = now.strftime("%Y/%m/%d %H:%M:%S")
    
    # --- 設定項目 ---
    output_filename = f"qr_labels_sheet_{timestamp_fs}.pdf"
    
    # ラベル・シート設定 (mm)
    margin_top = 8.8 * mm
    margin_left = 13.4 * mm 
    label_width = 48.3 * mm
    label_height = 25.5 * mm
    rows = 11
    cols = 4
    
    # QR・テキスト設定 (mm)
    qr_display_size = 17 * mm  # 3mm小さく
    text_height = 2.5 * mm
    text_margin_top = 0.5 * mm # QRとの隙間
    
    # ラベル内での相対位置（左に3mm）
    qr_offset_x = 3 * mm
    # QRとテキストを合わせた「塊」を上下中央に配置
    total_content_height = qr_display_size + text_margin_top + text_height
    start_y_in_label = (label_height - total_content_height) / 2
    
    # PDFキャンバスの作成
    page_width, page_height = A4
    c = canvas.Canvas(output_filename, pagesize=A4)
    
    temp_dir = "temp_qrs"
    if not os.path.exists(temp_dir):
        os.makedirs(temp_dir)

    print(f"PDF生成開始: {output_filename}")

    for r in range(rows):
        for col in range(cols):
            count = r * cols + col + 1
            content = f"{base_time_str}.{count:03d}"
            
            # QRコード生成
            qr = qrcode.QRCode(box_size=10, border=0)
            qr.add_data(content)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            qr_path = os.path.join(temp_dir, f"qr_{count}.png")
            img.save(qr_path)
            
            # 配置計算
            base_x = margin_left + (col * label_width) + qr_offset_x
            base_y = (page_height - margin_top) - (r * label_height) - label_height + start_y_in_label
            
            # 1. QRコードを描画 (上側に配置)
            qr_y = base_y + text_height + text_margin_top
            c.drawImage(qr_path, base_x, qr_y, width=qr_display_size, height=qr_display_size)
            
            # 2. テキストを描画 (下側に配置)
            c.setFont('HeiseiKakuGo-W5', 7) # 約2.5mm相当のサイズ
            # テキストの開始位置（QRの左端に合わせる）
            c.drawString(base_x, base_y + 0.5 * mm, content)

    c.showPage()
    c.save()
    print(f"完了！ '{output_filename}' が作成されました。")

if __name__ == "__main__":
    generate_qr_pdf()